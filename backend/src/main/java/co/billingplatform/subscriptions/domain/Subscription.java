package co.billingplatform.subscriptions.domain;

import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Suscripción vigente de un tenant (ver docs/PRICING.md y ADR-0011, ADR-0014).
 *
 * @param periodStart      inicio del periodo pagado; también ancla los ciclos mensuales de cuota
 * @param periodEnd        fin exclusivo del periodo pagado (un mes o un año)
 * @param quotaScope       MONTHLY: cuota por ciclo mensual; TOTAL: cuota única en todo el periodo (prueba gratis)
 * @param planLimits       límites del plan; {@code null} en un límite = sin límite
 * @param overrides        límites particulares (convenios) que reemplazan los del plan cuando no son {@code null}
 * @param overageUnitPrice precio por documento adicional sobre la cuota; {@code null} = el plan no admite excedentes
 */
public record Subscription(
        UUID id,
        TenantId tenantId,
        String planCode,
        Status status,
        LocalDate periodStart,
        LocalDate periodEnd,
        QuotaScope quotaScope,
        Limits planLimits,
        Limits overrides,
        BigDecimal overageUnitPrice) {

    public enum Status { TRIAL, ACTIVE, PAST_DUE, CANCELLED }

    public enum QuotaScope { MONTHLY, TOTAL }

    public record Limits(Integer documents, Integer maxUsers) {
        public static final Limits NONE = new Limits(null, null);
    }

    public Subscription {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(planCode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(periodStart);
        Objects.requireNonNull(periodEnd);
        Objects.requireNonNull(quotaScope);
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("period end must be after start");
        }
        if (overageUnitPrice != null && overageUnitPrice.signum() <= 0) {
            throw new IllegalArgumentException("overage price must be positive");
        }
        planLimits = planLimits == null ? Limits.NONE : planLimits;
        overrides = overrides == null ? Limits.NONE : overrides;
    }

    public Limits effectiveLimits() {
        return new Limits(
                overrides.documents() != null ? overrides.documents() : planLimits.documents(),
                overrides.maxUsers() != null ? overrides.maxUsers() : planLimits.maxUsers());
    }

    /** Solo prueba y suscripción al día emiten. Pago vencido o cancelada: no (decisión del propietario). */
    public boolean allowsIssuing() {
        return status == Status.TRIAL || status == Status.ACTIVE;
    }

    /** La prueba gratis nunca admite excedentes: al agotarla hay que suscribirse. */
    public boolean allowsOverage() {
        return overageUnitPrice != null && quotaScope == QuotaScope.MONTHLY && status == Status.ACTIVE;
    }

    public QuotaWindow quotaWindow(LocalDate today) {
        return quotaScope == QuotaScope.TOTAL
                ? new QuotaWindow(periodStart, periodEnd)
                : QuotaWindow.monthlyCycle(periodStart, today);
    }
}
