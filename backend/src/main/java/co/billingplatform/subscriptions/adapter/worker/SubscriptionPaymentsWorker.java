package co.billingplatform.subscriptions.adapter.worker;

import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.subscriptions.adapter.persistence.JdbcSubscriptionBilling;
import co.billingplatform.subscriptions.application.SubscriptionCheckoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Red de seguridad de los pagos de planes y vencimientos:
 * <ul>
 *   <li>consulta a Mercado Pago los checkouts pendientes (si el aviso no llegó o el cliente cerró
 *       la pestaña, el plan igual se activa);</li>
 *   <li>pasa a PAST_DUE las suscripciones vencidas después de los días de gracia.</li>
 * </ul>
 */
@Component
class SubscriptionPaymentsWorker {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentsWorker.class);

    private final JdbcSubscriptionBilling billing;
    private final ObjectProvider<SubscriptionCheckoutService> checkouts;
    private final TenantTransactions tx;
    private final int graceDays;
    private final Clock clock;

    SubscriptionPaymentsWorker(JdbcSubscriptionBilling billing, ObjectProvider<SubscriptionCheckoutService> checkouts,
                               TenantTransactions tx, @Value("${billing.subscriptions.grace-days:3}") int graceDays, Clock clock) {
        this.billing = billing;
        this.checkouts = checkouts;
        this.tx = tx;
        this.graceDays = graceDays;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${billing.subscriptions.reconcile-interval:2m}", initialDelayString = "${billing.subscriptions.reconcile-initial-delay:1m}")
    void reconcilePending() {
        SubscriptionCheckoutService service = checkouts.getIfAvailable();
        if (service == null) return;
        try {
            for (JdbcSubscriptionBilling.Claimed c : tx.withoutTenant(() -> billing.claimPending(20))) {
                try {
                    tx.inTenant(c.tenantId(), () -> service.reconcile(c.tenantId(), c.id()));
                } catch (RuntimeException e) {
                    log.warn("Subscription checkout {} could not be reconciled: {}", c.id(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not claim subscription checkouts: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${billing.subscriptions.overdue-interval:1h}", initialDelayString = "${billing.subscriptions.overdue-initial-delay:2m}")
    void markOverdue() {
        try {
            int changed = tx.withoutTenant(() -> billing.markOverdue(ColombianTime.today(clock), graceDays));
            if (changed > 0) {
                log.info("{} subscriptions moved to PAST_DUE", changed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not mark overdue subscriptions: {}", e.toString());
        }
    }
}
