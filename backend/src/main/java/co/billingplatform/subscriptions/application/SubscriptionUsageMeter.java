package co.billingplatform.subscriptions.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.application.UsageMeter;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.OverageConsent;
import co.billingplatform.subscriptions.domain.QuotaWindow;
import co.billingplatform.subscriptions.domain.Subscription;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementa la cuota de documentos (ADR-0011, ADR-0014).
 *
 * <ul>
 *   <li>Cuenta documentos emitidos (numerados) en PRODUCTION, por ciclo mensual aunque el pago sea anual.</li>
 *   <li>Al llegar al límite NO se cobra nada automáticamente: se responde {@code DOCUMENT_QUOTA_REACHED}
 *       para que la interfaz ofrezca mejorar el plan. Solo si el cliente acepta expresamente
 *       el excedente ({@link OverageConsentService}) se emite y se registra como cobrable.</li>
 *   <li>La prueba gratis no admite excedentes: al agotarla hay que suscribirse.</li>
 * </ul>
 */
public final class SubscriptionUsageMeter implements UsageMeter {

    private final SubscriptionRepository subscriptions;
    private final UsageRepository usage;
    private final OverageConsentRepository consents;
    private final Clock clock;

    public SubscriptionUsageMeter(SubscriptionRepository subscriptions, UsageRepository usage,
                                  OverageConsentRepository consents, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.usage = Objects.requireNonNull(usage);
        this.consents = Objects.requireNonNull(consents);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void ensureCanIssue(TenantId tenantId, ProviderEnvironment environment) {
        Subscription subscription = current(tenantId);
        if (!subscription.allowsIssuing()) {
            throw new DomainException("SUBSCRIPTION_INACTIVE", "The subscription does not allow issuing documents");
        }
        if (environment != ProviderEnvironment.PRODUCTION) {
            return;
        }
        Integer limit = subscription.effectiveLimits().documents();
        if (limit == null) {
            return;
        }
        QuotaWindow window = subscription.quotaWindow(today());
        if (usage.countIssued(tenantId, window.start(), window.endExclusive()) < limit) {
            return;
        }
        if (subscription.status() == Subscription.Status.TRIAL) {
            throw new DomainException("TRIAL_QUOTA_EXHAUSTED",
                    "The free trial documents were used; choose a plan to keep issuing");
        }
        if (!subscription.allowsOverage()) {
            throw new DomainException("DOCUMENT_QUOTA_EXCEEDED", "Document quota of the current cycle reached");
        }
        if (consentFor(subscription, window).isEmpty()) {
            throw new DomainException("DOCUMENT_QUOTA_REACHED",
                    "Plan limit reached: upgrade the plan or accept additional documents at %s each"
                            .formatted(subscription.overageUnitPrice().toPlainString()));
        }
    }

    @Override
    public void recordIssued(TenantId tenantId, UUID documentId, ProviderAccount account) {
        ProviderEnvironment environment = account.environment();
        if (environment == ProviderEnvironment.SIMULATION) {
            return;
        }
        BigDecimal overagePrice = null;
        if (environment == ProviderEnvironment.PRODUCTION) {
            Subscription subscription = current(tenantId);
            Integer limit = subscription.effectiveLimits().documents();
            if (limit != null) {
                QuotaWindow window = subscription.quotaWindow(today());
                if (usage.countIssued(tenantId, window.start(), window.endExclusive()) >= limit) {
                    overagePrice = consentFor(subscription, window)
                            .map(OverageConsent::unitPrice)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Over-quota document without consent: ensureCanIssue was not called"));
                }
            }
        }
        usage.recordIssued(tenantId, documentId, account.providerId().value(), environment.name(),
                clock.instant(), overagePrice);
    }

    private Subscription current(TenantId tenantId) {
        return subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
    }

    private Optional<OverageConsent> consentFor(Subscription subscription, QuotaWindow window) {
        return consents.find(subscription.tenantId(), subscription.id(), window.start());
    }

    private LocalDate today() {
        return ColombianTime.today(clock);
    }
}
