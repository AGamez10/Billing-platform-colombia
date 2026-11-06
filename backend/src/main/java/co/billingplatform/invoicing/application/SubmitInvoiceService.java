package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.application.ProviderRegistry;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.IdempotencyKey;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderCapabilities;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso: transmitir (o continuar transmitiendo) una factura finalizada.
 *
 * <p>Lo invoca el worker de la cola de envíos (tabla {@code submission_jobs}, ver
 * ADR-0006); es seguro invocarlo varias veces para la misma factura:
 * <ul>
 *   <li>la clave de idempotencia es estable por factura, no por intento;</li>
 *   <li>si un envío quedó con resultado incierto (timeout tras posible recepción),
 *       solo se reintenta si el proveedor deduplica por clave o permite buscar el
 *       documento por su número; si no, se exige reconciliación manual en lugar de
 *       arriesgar una factura duplicada;</li>
 *   <li>una factura usa la cuenta de proveedor fijada al finalizarla, aunque el tenant
 *       haya cambiado de proveedor entretanto.</li>
 * </ul>
 */
public final class SubmitInvoiceService {

    private final InvoiceRepository invoices;
    private final ProviderAccountRepository accounts;
    private final ProviderRegistry registry;
    private final IssuerDirectory issuers;
    private final Clock clock;
    private final int maxProviderInteractions;

    public SubmitInvoiceService(InvoiceRepository invoices, ProviderAccountRepository accounts,
                                ProviderRegistry registry, IssuerDirectory issuers, Clock clock,
                                int maxProviderInteractions) {
        this.invoices = Objects.requireNonNull(invoices);
        this.accounts = Objects.requireNonNull(accounts);
        this.registry = Objects.requireNonNull(registry);
        this.issuers = Objects.requireNonNull(issuers);
        this.clock = Objects.requireNonNull(clock);
        if (maxProviderInteractions < 1) {
            throw new IllegalArgumentException("maxProviderInteractions must be >= 1");
        }
        this.maxProviderInteractions = maxProviderInteractions;
    }

    public Invoice submit(TenantId tenantId, UUID invoiceId) {
        Invoice invoice = invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));

        if (invoice.status().isTerminal()) {
            return invoice;
        }
        if (invoice.status() == InvoiceStatus.DRAFT) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Draft invoices must be finalized before submission");
        }

        ProviderAccount account = resolveAccount(invoice);
        ElectronicInvoicingProvider provider = registry.providerFor(account, invoice.kind());

        SubmissionResult result = callProvider(invoice, account, provider);
        invoice.recordProviderResult(result, clock.instant());

        if (!invoice.status().isTerminal()
                && !invoice.outcomeUncertain()
                && invoice.providerInteractions() >= maxProviderInteractions) {
            invoice.recordProviderResult(new SubmissionResult.PermanentFailure(
                    "Maximum provider interactions reached: " + invoice.lastError().orElse("no final result")),
                    clock.instant());
        }
        invoices.save(invoice);
        return invoice;
    }

    /** La cuenta se fijó al finalizar: se usa esa aunque el tenant haya activado otra después. */
    private ProviderAccount resolveAccount(Invoice invoice) {
        UUID accountId = invoice.providerAccountId().orElseThrow(() ->
                new DomainException("INVALID_STATE_TRANSITION", "Invoice has no provider account bound"));
        return accounts.findById(invoice.tenantId(), accountId)
                .orElseThrow(() -> new DomainException("PROVIDER_ACCOUNT_NOT_FOUND",
                        "The provider account used for this invoice no longer exists"));
    }

    private SubmissionResult callProvider(Invoice invoice, ProviderAccount account, ElectronicInvoicingProvider provider) {
        ProviderCapabilities caps = provider.capabilities();
        IdempotencyKey key = IdempotencyKey.forDocument(invoice.kind(), invoice.id());
        try {
            if (invoice.status() == InvoiceStatus.SUBMITTED && invoice.externalReference().isPresent()) {
                if (!caps.statusQuery()) {
                    // Sin consulta de estado, el resultado llegará por webhook.
                    return new SubmissionResult.Pending(invoice.externalReference().get());
                }
                return provider.queryStatus(account, invoice.externalReference().get());
            }

            if (invoice.outcomeUncertain()) {
                if (caps.idempotentSubmission()) {
                    return provider.submit(account, toDocument(invoice), key);
                }
                if (caps.lookupByDocumentNumber()) {
                    String number = invoice.number().orElseThrow().formatted();
                    Optional<SubmissionResult> found = provider.findByDocumentNumber(account, number);
                    if (found.isPresent()) {
                        return found.get();
                    }
                    return provider.submit(account, toDocument(invoice), key);
                }
                throw new DomainException("RECONCILIATION_REQUIRED",
                        "Submission outcome is uncertain and provider cannot deduplicate; manual reconciliation required");
            }

            return provider.submit(account, toDocument(invoice), key);
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            // Un adaptador no debería lanzar, pero si lo hace no podemos descartar que el
            // proveedor haya recibido el documento: se trata como resultado incierto.
            return new SubmissionResult.TransientFailure("provider adapter error: " + e.getClass().getSimpleName(), true);
        }
    }

    private co.billingplatform.electronicinvoicing.domain.ElectronicDocument toDocument(Invoice invoice) {
        return ElectronicDocumentMapper.toDocument(invoice, issuers.issuerOf(invoice.tenantId()));
    }
}
