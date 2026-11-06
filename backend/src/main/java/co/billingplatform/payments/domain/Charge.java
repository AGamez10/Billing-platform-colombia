package co.billingplatform.payments.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cobro de una venta POS a través de la pasarela del comercio.
 *
 * <p>Estados: PENDING → PAID | REJECTED | EXPIRED | CANCELLED. Solo PAID dispara la emisión del
 * documento. Un estado final no cambia: un aviso tardío o duplicado de la pasarela no revive un
 * cobro ni lo paga dos veces.
 */
public final class Charge {

    public enum Status {
        PENDING, PAID, REJECTED, EXPIRED, CANCELLED;

        public boolean isFinal() {
            return this != PENDING;
        }
    }

    private final UUID id;
    private final TenantId tenantId;
    private final UUID invoiceId;
    private final UUID paymentAccountId;
    private final UUID createdBy;
    private final BigDecimal amount;
    private Status status;
    private String gatewayLinkId;
    private String checkoutUrl;
    private String gatewayTransactionId;
    private String paymentMethod;
    private Instant paidAt;

    public Charge(UUID id, TenantId tenantId, UUID invoiceId, UUID paymentAccountId, UUID createdBy, BigDecimal amount,
                  Status status,
                  String gatewayLinkId, String checkoutUrl, String gatewayTransactionId, String paymentMethod,
                  Instant paidAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.invoiceId = Objects.requireNonNull(invoiceId);
        this.paymentAccountId = Objects.requireNonNull(paymentAccountId);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.amount = Objects.requireNonNull(amount);
        this.status = Objects.requireNonNull(status);
        this.gatewayLinkId = gatewayLinkId;
        this.checkoutUrl = checkoutUrl;
        this.gatewayTransactionId = gatewayTransactionId;
        this.paymentMethod = paymentMethod;
        this.paidAt = paidAt;
    }

    /**
     * Las pasarelas cobran pesos enteros. Si el total del documento tiene centavos, se cobra el
     * peso más cercano (mitad hacia arriba); la diferencia es de máximo $0,50.
     */
    public static Charge open(TenantId tenantId, UUID invoiceId, UUID paymentAccountId, UUID createdBy, BigDecimal payable) {
        BigDecimal whole = payable.setScale(0, RoundingMode.HALF_UP);
        if (whole.signum() <= 0) {
            throw new DomainException("CHARGE_AMOUNT_INVALID", "The amount to charge must be positive");
        }
        return new Charge(UUID.randomUUID(), tenantId, invoiceId, paymentAccountId, createdBy, whole.setScale(2), Status.PENDING,
                null, null, null, null, null);
    }

    public void linkCreated(String linkId, String url) {
        this.gatewayLinkId = Objects.requireNonNull(linkId);
        this.checkoutUrl = Objects.requireNonNull(url);
    }

    /** @return true si este aviso cambió el estado (para emitir el documento una sola vez). */
    public boolean apply(Status reported, String transactionId, String method, Instant now) {
        if (status.isFinal() || reported == Status.PENDING) {
            return false;
        }
        status = reported;
        gatewayTransactionId = transactionId;
        paymentMethod = method;
        if (reported == Status.PAID) {
            paidAt = now;
        }
        return true;
    }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public UUID invoiceId() { return invoiceId; }
    public UUID paymentAccountId() { return paymentAccountId; }
    public UUID createdBy() { return createdBy; }
    public BigDecimal amount() { return amount; }
    public Status status() { return status; }
    public Optional<String> gatewayLinkId() { return Optional.ofNullable(gatewayLinkId); }
    public Optional<String> checkoutUrl() { return Optional.ofNullable(checkoutUrl); }
    public Optional<String> gatewayTransactionId() { return Optional.ofNullable(gatewayTransactionId); }
    public Optional<String> paymentMethod() { return Optional.ofNullable(paymentMethod); }
    public Optional<Instant> paidAt() { return Optional.ofNullable(paidAt); }
}
