package co.billingplatform.insights.adapter.worker;

import co.billingplatform.insights.adapter.persistence.JdbcAlertLedger;
import co.billingplatform.insights.application.AlertNotifier;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Revisa cada hora los pendientes de cada organización y avisa lo nuevo. Un fallo en una
 * organización no detiene a las demás; lo ya avisado no se repite (clave en alert_notifications).
 */
@Component
@ConditionalOnProperty(name = "billing.alerts.enabled", havingValue = "true", matchIfMissing = true)
class AlertWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);

    private final AlertNotifier notifier;
    private final JdbcAlertLedger ledger;
    private final TenantTransactions tx;

    AlertWorker(AlertNotifier notifier, JdbcAlertLedger ledger, TenantTransactions tx) {
        this.notifier = notifier;
        this.ledger = ledger;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${billing.alerts.interval:1h}", initialDelayString = "${billing.alerts.initial-delay:5m}")
    void run() {
        int sent = 0;
        try {
            for (TenantId tenant : tx.withoutTenant(ledger::organizations)) {
                try {
                    sent += tx.inTenant(tenant, () -> notifier.notify(tenant));
                } catch (RuntimeException e) {
                    log.warn("Alerts failed for tenant {}: {}", tenant.value(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not list organizations for alerts: {}", e.toString());
        }
        if (sent > 0) {
            log.info("Sent {} alert(s)", sent);
        }
    }
}
