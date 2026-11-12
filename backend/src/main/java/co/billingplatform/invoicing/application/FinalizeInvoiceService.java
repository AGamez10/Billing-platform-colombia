package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Caso de uso: finalizar (numerar) un borrador y programar su transmisión.
 *
 * <p>Todo ocurre en una transacción: verificación de cuota, bloqueo de la resolución,
 * asignación de número, cambio de estado, job en el outbox, registro de consumo y
 * auditoría. Si algo falla, nada se confirma y no se consume número.
 *
 * <p>Repetir la llamada sobre una factura ya finalizada no hace nada (doble clic, reintento
 * de red): devuelve la factura tal como está.
 */
public final class FinalizeInvoiceService {

    private final InvoiceRepository invoices;
    private final NumberingRangeRepository ranges;
    private final ProviderAccountRepository accounts;
    private final SubmissionQueue queue;
    private final UsageMeter usage;
    private final TaxCatalog taxes;
    private final DocumentCalculator calculator;
    private final AuditTrail audit;
    private final IssuerDirectory issuers;
    private final Clock clock;

    public FinalizeInvoiceService(InvoiceRepository invoices, NumberingRangeRepository ranges,
                                  ProviderAccountRepository accounts, SubmissionQueue queue, UsageMeter usage,
                                  TaxCatalog taxes, DocumentCalculator calculator, AuditTrail audit,
                                  IssuerDirectory issuers, Clock clock) {
        this.issuers = Objects.requireNonNull(issuers);
        this.invoices = Objects.requireNonNull(invoices);
        this.ranges = Objects.requireNonNull(ranges);
        this.accounts = Objects.requireNonNull(accounts);
        this.queue = Objects.requireNonNull(queue);
        this.usage = Objects.requireNonNull(usage);
        this.taxes = Objects.requireNonNull(taxes);
        this.calculator = Objects.requireNonNull(calculator);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Comprueba, sin emitir nada, que un documento de este tipo podría emitirse ahora: proveedor,
     * suscripción, cupo y resolución vigente. Se usa antes de cobrarle a un cliente, para no recibir
     * un pago cuyo documento luego no pueda salir.
     */
    public void ensureCanFinalize(TenantId tenantId, DocumentKind kind) {
        ProviderAccount account = accounts.findActive(tenantId)
                .orElseThrow(() -> new DomainException("NO_PROVIDER_CONFIGURED",
                        "The organization has no electronic invoicing provider configured"));
        usage.ensureCanIssue(tenantId, account.environment());
        ensureFiscalData(tenantId, account.environment(), null);
        ranges.lockActiveRange(tenantId, kind, account.environment(), ColombianTime.today(clock))
                .orElseThrow(() -> new DomainException("NUMBERING_RANGE_NOT_FOUND",
                        "No active %s numbering resolution for %s".formatted(kind, account.environment())));
    }

    public Invoice finalizeInvoice(TenantId tenantId, Actor actor, UUID invoiceId) {
        Invoice invoice = invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        if (invoice.status() != InvoiceStatus.DRAFT) {
            return invoice;
        }

        ProviderAccount account = accounts.findActive(tenantId)
                .orElseThrow(() -> new DomainException("NO_PROVIDER_CONFIGURED",
                        "The organization has no electronic invoicing provider configured"));
        usage.ensureCanIssue(tenantId, account.environment());
        ensureFiscalData(tenantId, account.environment(), invoice.kind() == DocumentKind.INVOICE ? invoice.customer() : null);

        LocalDate issueDate = ColombianTime.today(clock);
        NumberingRange range = ranges.lockActiveRange(tenantId, invoice.kind(), account.environment(), issueDate)
                .orElseThrow(() -> new DomainException("NUMBERING_RANGE_NOT_FOUND",
                        "No active %s numbering resolution for %s".formatted(invoice.kind(), account.environment())));

        invoice.finalizeWith(range, account, issueDate, taxes, calculator, clock.instant());

        ranges.save(range);
        invoices.save(invoice);
        queue.enqueue(tenantId, invoice.id());
        usage.recordIssued(tenantId, invoice.id(), account);
        audit.record(new AuditTrail.Event(tenantId, actor, "INVOICE_FINALIZED", "invoice", invoice.id().toString(),
                Map.of("number", invoice.number().orElseThrow().formatted(),
                        "environment", account.environment().name(),
                        "provider", account.providerId().value())));
        return invoice;
    }

    /**
     * En habilitación y producción el documento viaja al proveedor y a la DIAN: el emisor necesita
     * perfil tributario y dirección, y el adquiriente de una factura su perfil tributario. En
     * simulación (práctica) no se exige, para poder probar antes de completar la configuración.
     */
    private void ensureFiscalData(TenantId tenantId, ProviderEnvironment environment, Party customer) {
        if (environment == ProviderEnvironment.SIMULATION) {
            return;
        }
        Party issuer = issuers.issuerOf(tenantId);
        if (issuer.fiscal() == null || issuer.address() == null || issuer.email() == null) {
            throw new DomainException("ISSUER_PROFILE_INCOMPLETE",
                    "Complete the organization's tax profile and address before issuing in " + environment);
        }
        if (customer != null && !customer.isFinalConsumer() && customer.fiscal() == null) {
            throw new DomainException("CUSTOMER_PROFILE_INCOMPLETE",
                    "Complete the customer's tax profile and save the draft again before issuing");
        }
    }
}
