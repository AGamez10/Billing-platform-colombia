package co.billingplatform.payments.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.application.FinalizeInvoiceService;
import co.billingplatform.invoicing.application.InvoiceDraftService;
import co.billingplatform.invoicing.application.InvoiceRepository;
import co.billingplatform.invoicing.application.PosSaleService;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.payments.port.MerchantPaymentGateway;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * "Cobra y la factura sale sola" (ADR-0020).
 *
 * <ol>
 *   <li>{@link #start}: la caja arma la venta. Se crea el POS en borrador (el total lo calcula el
 *       servidor) y un link de pago por ese total en la cuenta de pasarela del comercio.</li>
 *   <li>{@link #refresh}: se consulta a la pasarela, la fuente de verdad. Si el cobro quedó pagado,
 *       el POS se emite en la misma transacción. Lo invocan la pantalla de cobro, el webhook y
 *       la conciliación; ninguno puede emitir dos veces, porque el cobro se bloquea y un estado
 *       final no cambia.</li>
 * </ol>
 *
 * <p>Ambientes coherentes: un cobro de PRUEBA solo emite documentos de simulación o habilitación,
 * y un cobro REAL solo emite en producción. Nunca un pago real con un documento simulado, ni un
 * documento con validez tributaria por un pago de prueba.
 */
public final class ChargeService {

    /**
     * @param issueBlockedCode si el pago está aprobado pero el documento no pudo emitirse todavía
     *                         (p. ej. cupo agotado), el código del motivo; la venta queda en borrador
     *                         y se emite al resolverlo y volver a consultar el cobro.
     */
    public record Started(Charge charge, Invoice invoice, String issueBlockedCode) {
        public Started(Charge charge, Invoice invoice) {
            this(charge, invoice, null);
        }
    }

    private final InvoiceDraftService drafts;
    private final FinalizeInvoiceService finalizer;
    private final InvoiceRepository invoices;
    private final ProviderAccountRepository providerAccounts;
    private final PaymentPorts.PaymentAccounts paymentAccounts;
    private final PaymentPorts.Charges charges;
    private final PaymentPorts.SecretVault vault;
    private final MerchantPaymentGateway gateway;
    private final PosSaleService.IdempotencyKeys keys;
    private final AuditTrail audit;
    private final Clock clock;

    public ChargeService(InvoiceDraftService drafts, FinalizeInvoiceService finalizer, InvoiceRepository invoices,
                         ProviderAccountRepository providerAccounts, PaymentPorts.PaymentAccounts paymentAccounts,
                         PaymentPorts.Charges charges, PaymentPorts.SecretVault vault, MerchantPaymentGateway gateway,
                         PosSaleService.IdempotencyKeys keys, AuditTrail audit, Clock clock) {
        this.drafts = Objects.requireNonNull(drafts);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.invoices = Objects.requireNonNull(invoices);
        this.providerAccounts = Objects.requireNonNull(providerAccounts);
        this.paymentAccounts = Objects.requireNonNull(paymentAccounts);
        this.charges = Objects.requireNonNull(charges);
        this.vault = Objects.requireNonNull(vault);
        this.gateway = Objects.requireNonNull(gateway);
        this.keys = Objects.requireNonNull(keys);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Antes de cobrar, en su propia transacción corta: ¿el documento podrá emitirse? Así nunca se
     * recibe un pago cuya venta no pueda documentarse (sin resolución, suscripción vencida, cupo).
     */
    public void preflight(TenantId tenantId) {
        finalizer.ensureCanFinalize(tenantId, DocumentKind.POS_EQUIVALENT);
    }

    public Started start(TenantId tenantId, UUID userId, String idempotencyKey, UUID customerId, List<InvoiceLine> lines,
                         String canonicalRequest) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new DomainException("IDEMPOTENCY_KEY_REQUIRED",
                    "Header Idempotency-Key is required (8 to 128 letters, digits, '-' or '_'), one per sale");
        }
        byte[] hash = sha256("charge:" + canonicalRequest);
        Optional<PosSaleService.IdempotencyKeys.Stored> previous = keys.reserve(tenantId, idempotencyKey, hash);
        if (previous.isPresent()) {
            PosSaleService.IdempotencyKeys.Stored stored = previous.get();
            if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
                throw new DomainException("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different sale");
            }
            if (stored.resourceId() == null) {
                throw new DomainException("IDEMPOTENCY_KEY_IN_PROGRESS", "The sale with this key is still being processed");
            }
            Charge charge = charges.lock(tenantId, stored.resourceId()).orElseThrow(ChargeService::notFound);
            return new Started(charge, invoice(tenantId, charge.invoiceId()));
        }

        PaymentAccount account = paymentAccounts.findActive(tenantId)
                .orElseThrow(() -> new DomainException("PAYMENT_ACCOUNT_REQUIRED", "Connect a payment account first"));
        ProviderEnvironment environment = providerAccounts.findActive(tenantId)
                .orElseThrow(() -> new DomainException("NO_PROVIDER_CONFIGURED", "No electronic invoicing provider configured"))
                .environment();
        boolean live = account.mode() == PaymentAccount.Mode.LIVE;
        if (live != (environment == ProviderEnvironment.PRODUCTION)) {
            throw new DomainException("PAYMENT_ENVIRONMENT_MISMATCH", live
                    ? "Live payments require the invoicing provider in PRODUCTION"
                    : "Test payments cannot issue documents in PRODUCTION");
        }

        Actor actor = Actor.user(userId);
        Invoice draft = drafts.create(tenantId, actor,
                new InvoiceDraftService.DraftCommand(DocumentKind.POS_EQUIVALENT, customerId, lines));
        Charge charge = Charge.open(tenantId, draft.id(), account.id(), userId, draft.calculation().payableAmount());
        MerchantPaymentGateway.CreatedLink link = callGateway(() ->
                gateway.createLink(credentials(account), charge.id().toString(), charge.amount(), "Venta " + draft.id().toString().substring(0, 8)));
        charge.linkCreated(link.linkId(), link.checkoutUrl());
        charges.insert(charge);
        keys.complete(tenantId, idempotencyKey, charge.id());
        audit.record(new AuditTrail.Event(tenantId, actor, "CHARGE_STARTED", "charge", charge.id().toString(),
                Map.of("invoiceId", draft.id().toString(), "amount", charge.amount().toPlainString(),
                        "gateway", account.gateway().name(), "mode", account.mode().name())));
        return new Started(charge, draft);
    }

    /**
     * Consulta la pasarela y aplica el resultado. Si el pago quedó aprobado, emite el POS.
     * Si la pasarela no responde, el cobro sigue PENDING y se reintenta en la próxima consulta.
     */
    public Started refresh(TenantId tenantId, UUID chargeId) {
        Charge charge = charges.lock(tenantId, chargeId).orElseThrow(ChargeService::notFound);
        boolean changed = false;
        if (charge.status() == Charge.Status.PENDING && charge.gatewayLinkId().isPresent()) {
            PaymentAccount account = paymentAccounts.findById(tenantId, charge.paymentAccountId()).orElseThrow(ChargeService::notFound);
            Optional<MerchantPaymentGateway.LinkStatus> status = Optional.empty();
            try {
                status = Optional.of(gateway.status(credentials(account), charge.gatewayLinkId().get()));
            } catch (MerchantPaymentGateway.GatewayException e) {
                // Sin respuesta: queda pendiente; la siguiente consulta lo resuelve.
            }
            if (status.isPresent() && charge.apply(status.get().status(), status.get().transactionId(),
                    status.get().paymentMethod(), clock.instant())) {
                charges.update(charge);
                changed = true;
                audit.record(new AuditTrail.Event(tenantId, Actor.user(charge.createdBy()), "CHARGE_" + charge.status().name(),
                        "charge", charge.id().toString(),
                        Map.of("paymentMethod", String.valueOf(charge.paymentMethod().orElse("")))));
            }
        }
        Invoice invoice = invoice(tenantId, charge.invoiceId());
        if (charge.status() == Charge.Status.PAID && invoice.status() == InvoiceStatus.DRAFT) {
            // El dinero ya está recibido: la venta se documenta sí o sí. Si algo lo impide ahora,
            // queda en borrador con el motivo y se reintenta en cada consulta.
            String blocked;
            if (invoice.calculation().payableAmount().setScale(0, RoundingMode.HALF_UP).compareTo(charge.amount()) != 0) {
                // El borrador se editó después de crear el cobro: no se documenta un valor distinto al cobrado.
                blocked = "CHARGE_AMOUNT_MISMATCH";
            } else {
                try {
                    return new Started(charge, finalizer.finalizeInvoice(tenantId, Actor.user(charge.createdBy()), invoice.id()));
                } catch (DomainException e) {
                    blocked = e.code();
                }
            }
            if (changed) {
                audit.record(new AuditTrail.Event(tenantId, Actor.user(charge.createdBy()), "CHARGE_ISSUE_BLOCKED",
                        "charge", charge.id().toString(), Map.of("code", blocked)));
            }
            return new Started(charge, invoice, blocked);
        }
        return new Started(charge, invoice);
    }

    /** Sin tenant en contexto: ubica la cuenta dueña de la URL del webhook. */
    public Optional<PaymentAccount> webhookAccount(String token) {
        return paymentAccounts.findByWebhookToken(token);
    }

    /**
     * Webhook de la pasarela, ya dentro del tenant de la cuenta. No se confía en su cuerpo: una
     * firma válida solo indica qué cobros consultar (los pendientes recientes del comercio), y
     * cada uno se consulta a la pasarela con {@link #refresh} en su propia transacción.
     */
    public List<UUID> verifyWebhook(PaymentAccount account, byte[] body, String signature) {
        if (!gateway.verifyWebhook(credentials(account), body, signature)) {
            throw new DomainException("WEBHOOK_SIGNATURE_INVALID", "Invalid webhook signature");
        }
        return pendingRecent(account.tenantId());
    }

    /** Un cobro con llaves de PRUEBA: no mueve dinero real (la interfaz lo advierte). */
    public boolean isTestCharge(TenantId tenantId, Charge charge) {
        return paymentAccounts.findById(tenantId, charge.paymentAccountId())
                .map(a -> a.mode() == PaymentAccount.Mode.TEST).orElse(false);
    }

    public List<UUID> pendingRecent(TenantId tenantId) {
        return charges.pendingSince(tenantId, clock.instant().minus(Duration.ofHours(24)));
    }

    private Invoice invoice(TenantId tenantId, UUID invoiceId) {
        return invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
    }

    private MerchantPaymentGateway.Credentials credentials(PaymentAccount account) {
        return new MerchantPaymentGateway.Credentials(account.mode(), vault.read(account.identityKeyRef()),
                vault.read(account.secretKeyRef()));
    }

    private static <T> T callGateway(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (MerchantPaymentGateway.GatewayException e) {
            throw e.credentialsRejected()
                    ? new DomainException("PAYMENT_CREDENTIALS_REJECTED", "The payment gateway rejected the account keys")
                    : new DomainException("PAYMENT_GATEWAY_UNAVAILABLE", "The payment gateway is not responding");
        }
    }

    private static DomainException notFound() {
        return new DomainException("CHARGE_NOT_FOUND", "Charge not found");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
