package co.billingplatform.subscriptions.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.application.UsageMeter;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.Subscription;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementa la cuota de documentos que exige facturación.
 *
 * <p>Decisión de producto registrada: cuenta los documentos <em>emitidos</em> (numerados),
 * no solo los validados, porque cada número consume la resolución y genera costo con el
 * proveedor. Solo los documentos de PRODUCTION consumen cuota; los de habilitación se
 * registran (para medir costos de pruebas) pero no cuentan; los simulados no se registran.
 */
public final class SubscriptionUsageMeter implements UsageMeter {

    private final SubscriptionRepository subscriptions;
    private final UsageRepository usage;
    private final Clock clock;

    public SubscriptionUsageMeter(SubscriptionRepository subscriptions, UsageRepository usage, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.usage = Objects.requireNonNull(usage);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void ensureCanIssue(TenantId tenantId) {
        Subscription subscription = subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
        if (!subscription.allowsIssuing()) {
            throw new DomainException("SUBSCRIPTION_INACTIVE", "The subscription does not allow issuing documents");
        }
        Integer limit = subscription.effectiveLimits().monthlyDocuments();
        if (limit == null) {
            return;
        }
        long issued = usage.countIssued(tenantId, subscription.periodStart(), subscription.periodEnd());
        if (issued >= limit) {
            throw new DomainException("DOCUMENT_QUOTA_EXCEEDED",
                    "Document quota of the current period reached (%d)".formatted(limit));
        }
    }

    @Override
    public void recordIssued(TenantId tenantId, UUID documentId, ProviderAccount account) {
        if (account.environment() == ProviderEnvironment.SIMULATION) {
            return;
        }
        usage.recordIssued(tenantId, documentId, account.providerId().value(), account.environment().name(), clock.instant());
    }
}
