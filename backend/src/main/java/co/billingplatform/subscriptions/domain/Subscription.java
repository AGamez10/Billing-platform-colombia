package co.billingplatform.subscriptions.domain;

import co.billingplatform.shared.domain.TenantId;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Suscripción vigente de un tenant. Las condiciones particulares de un cliente (p. ej. un
 * convenio) son sobrescrituras genéricas de límites, nunca código especial por cliente.
 *
 * @param planLimits límites del plan; {@code null} en un límite = sin límite
 * @param overrides  límites particulares que reemplazan los del plan cuando no son {@code null}
 */
public record Subscription(
        TenantId tenantId,
        String planCode,
        Status status,
        LocalDate periodStart,
        LocalDate periodEnd,
        Limits planLimits,
        Limits overrides) {

    public enum Status {
        TRIAL, ACTIVE,
        /** Pago vencido: se permite seguir facturando (periodo de gracia, decisión de producto pendiente de confirmar). */
        PAST_DUE,
        CANCELLED
    }

    public record Limits(Integer monthlyDocuments, Integer maxUsers) {
        public static final Limits NONE = new Limits(null, null);
    }

    public Subscription {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(planCode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(periodStart);
        Objects.requireNonNull(periodEnd);
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("period end must be after start");
        }
        planLimits = planLimits == null ? Limits.NONE : planLimits;
        overrides = overrides == null ? Limits.NONE : overrides;
    }

    public Limits effectiveLimits() {
        return new Limits(
                overrides.monthlyDocuments() != null ? overrides.monthlyDocuments() : planLimits.monthlyDocuments(),
                overrides.maxUsers() != null ? overrides.maxUsers() : planLimits.maxUsers());
    }

    public boolean allowsIssuing() {
        return status != Status.CANCELLED;
    }
}
