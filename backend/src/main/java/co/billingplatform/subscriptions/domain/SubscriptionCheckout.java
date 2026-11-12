package co.billingplatform.subscriptions.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Un intento de pago de la suscripción: plan, ciclo y valor quedan fijos al crearlo. Solo pasa a
 * PAID una vez, con el pago aprobado que lo respalda; PAID y EXPIRED no cambian más.
 */
public final class SubscriptionCheckout {

    public enum Status { PENDING, PAID, EXPIRED }

    private final UUID id;
    private final TenantId tenantId;
    private final String planCode;
    private final PlanChangeRequest.Cycle cycle;
    private final BigDecimal amount;
    private final UUID createdBy;
    private final Instant createdAt;
    private Status status;
    private String preferenceId;
    private String checkoutUrl;
    private String gatewayPaymentId;
    private Instant paidAt;

    public SubscriptionCheckout(UUID id, TenantId tenantId, String planCode, PlanChangeRequest.Cycle cycle, BigDecimal amount,
                                UUID createdBy, Instant createdAt, Status status, String preferenceId, String checkoutUrl,
                                String gatewayPaymentId, Instant paidAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.planCode = Objects.requireNonNull(planCode);
        this.cycle = Objects.requireNonNull(cycle);
        this.amount = Objects.requireNonNull(amount);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.status = Objects.requireNonNull(status);
        this.preferenceId = preferenceId;
        this.checkoutUrl = checkoutUrl;
        this.gatewayPaymentId = gatewayPaymentId;
        this.paidAt = paidAt;
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public static SubscriptionCheckout open(TenantId tenantId, String planCode, PlanChangeRequest.Cycle cycle, BigDecimal amount,
                                            UUID createdBy, Instant now) {
        return new SubscriptionCheckout(UUID.randomUUID(), tenantId, planCode, cycle, amount, createdBy, now, Status.PENDING,
                null, null, null, null);
    }

    public void preferenceCreated(String preferenceId, String checkoutUrl) {
        this.preferenceId = Objects.requireNonNull(preferenceId);
        this.checkoutUrl = Objects.requireNonNull(checkoutUrl);
    }

    /** @return false si ya estaba pagado con ese mismo pago (aviso repetido) */
    public boolean markPaid(String paymentId, Instant at) {
        Objects.requireNonNull(paymentId);
        if (status == Status.PAID) {
            if (paymentId.equals(gatewayPaymentId)) {
                return false;
            }
            throw new DomainException("CHECKOUT_ALREADY_PAID", "This checkout was already paid with another payment");
        }
        if (status == Status.EXPIRED) {
            throw new DomainException("CHECKOUT_EXPIRED", "This checkout expired");
        }
        status = Status.PAID;
        gatewayPaymentId = paymentId;
        paidAt = Objects.requireNonNull(at);
        return true;
    }

    public void expire() {
        if (status == Status.PENDING) {
            status = Status.EXPIRED;
        }
    }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public String planCode() { return planCode; }
    public PlanChangeRequest.Cycle cycle() { return cycle; }
    public BigDecimal amount() { return amount; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public Optional<String> preferenceId() { return Optional.ofNullable(preferenceId); }
    public Optional<String> checkoutUrl() { return Optional.ofNullable(checkoutUrl); }
    public Optional<String> gatewayPaymentId() { return Optional.ofNullable(gatewayPaymentId); }
    public Optional<Instant> paidAt() { return Optional.ofNullable(paidAt); }
}
