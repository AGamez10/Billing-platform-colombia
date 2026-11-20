package co.billingplatform.integrations.adapter.worker;

import co.billingplatform.integrations.adapter.persistence.JdbcWebhookStore;
import co.billingplatform.integrations.application.WebhookService;
import co.billingplatform.platform.persistence.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Entrega los avisos salientes vencidos; cada uno en su transacción y su organización. */
@Component
@ConditionalOnProperty(name = "billing.webhooks.worker-enabled", havingValue = "true", matchIfMissing = true)
class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final JdbcWebhookStore store;
    private final WebhookService webhooks;
    private final TenantTransactions tx;

    WebhookDeliveryWorker(JdbcWebhookStore store, WebhookService webhooks, TenantTransactions tx) {
        this.store = store;
        this.webhooks = webhooks;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${billing.webhooks.interval:15s}", initialDelayString = "${billing.webhooks.initial-delay:30s}")
    void run() {
        try {
            for (JdbcWebhookStore.Claimed c : tx.withoutTenant(() -> store.claim(25))) {
                try {
                    tx.inTenant(c.tenantId(), () -> {
                        webhooks.deliver(c.tenantId(), c.id());
                        return null;
                    });
                } catch (RuntimeException e) {
                    log.warn("Webhook delivery {} failed: {}", c.id(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not claim webhook deliveries: {}", e.toString());
        }
    }
}
