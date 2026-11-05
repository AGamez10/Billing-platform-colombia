package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.OverageConsent;
import co.billingplatform.subscriptions.domain.QuotaWindow;
import co.billingplatform.subscriptions.domain.Subscription;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Consulta de cuota y aceptación expresa de excedentes. La interfaz muestra primero
 * {@link #status} (con la opción de mejorar el plan) y solo llama {@link #accept} cuando
 * el usuario elige explícitamente pagar los documentos adicionales.
 */
public final class OverageConsentService {

    public record QuotaStatus(String planCode, Integer limit, long used, QuotaWindow window,
                              boolean overageAvailable, BigDecimal overageUnitPrice, boolean overageAccepted) {
        public boolean limitReached() {
            return limit != null && used >= limit;
        }
    }

    private final SubscriptionRepository subscriptions;
    private final UsageRepository usage;
    private final OverageConsentRepository consents;
    private final AuditTrail audit;
    private final Clock clock;

    public OverageConsentService(SubscriptionRepository subscriptions, UsageRepository usage,
                                 OverageConsentRepository consents, AuditTrail audit, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.usage = Objects.requireNonNull(usage);
        this.consents = Objects.requireNonNull(consents);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public QuotaStatus status(TenantId tenantId) {
        Subscription s = current(tenantId);
        QuotaWindow window = s.quotaWindow(ColombianTime.today(clock));
        long used = usage.countIssued(tenantId, window.start(), window.endExclusive());
        boolean accepted = consents.find(tenantId, s.id(), window.start()).isPresent();
        return new QuotaStatus(s.planCode(), s.effectiveLimits().documents(), used, window,
                s.allowsOverage(), s.allowsOverage() ? s.overageUnitPrice() : null, accepted);
    }

    /** Idempotente: aceptar dos veces en el mismo ciclo no crea otra aceptación ni cambia el precio. */
    public OverageConsent accept(TenantId tenantId, Actor actor) {
        Subscription s = current(tenantId);
        if (!s.allowsOverage()) {
            throw new DomainException("OVERAGE_NOT_AVAILABLE", "The current plan does not allow additional documents");
        }
        QuotaWindow window = s.quotaWindow(ColombianTime.today(clock));
        return consents.find(tenantId, s.id(), window.start()).orElseGet(() -> {
            OverageConsent consent = new OverageConsent(tenantId, s.id(), window, s.overageUnitPrice(), actor, clock.instant());
            consents.save(consent);
            audit.record(new AuditTrail.Event(tenantId, actor, "OVERAGE_ACCEPTED", "subscription", s.id().toString(),
                    Map.of("unitPrice", s.overageUnitPrice().toPlainString(),
                            "windowStart", window.start().toString(),
                            "windowEnd", window.endExclusive().toString())));
            return consent;
        });
    }

    private Subscription current(TenantId tenantId) {
        return subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
    }
}
