package co.billingplatform.payments.adapter.worker;

import co.billingplatform.payments.adapter.persistence.JdbcCharges;
import co.billingplatform.payments.adapter.persistence.JdbcCharges.ClaimedCharge;
import co.billingplatform.payments.application.ChargeService;
import co.billingplatform.platform.persistence.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Red de seguridad del cobro: consulta a la pasarela los cobros pendientes de las últimas 24 h.
 * Si la pantalla de cobro se cerró o el webhook no llegó, el pago igual termina con su documento.
 */
@Component
class ChargeReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChargeReconciler.class);

    private final JdbcCharges charges;
    private final ChargeService service;
    private final TenantTransactions tx;
    private final int batchSize;

    ChargeReconciler(JdbcCharges charges, ChargeService service, TenantTransactions tx,
                     @Value("${billing.payments.reconcile-batch-size:20}") int batchSize) {
        this.charges = charges;
        this.service = service;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${billing.payments.reconcile-interval:30s}",
            initialDelayString = "${billing.payments.reconcile-initial-delay:30s}")
    void poll() {
        List<ClaimedCharge> claimed;
        try {
            claimed = tx.withoutTenant(() -> charges.claimPending(batchSize));
        } catch (RuntimeException e) {
            log.warn("Could not claim pending charges: {}", e.toString());
            return;
        }
        for (ClaimedCharge c : claimed) {
            try {
                tx.inTenant(c.tenantId(), () -> service.refresh(c.tenantId(), c.id()));
            } catch (RuntimeException e) {
                log.warn("Charge {} could not be reconciled: {}", c.id(), e.toString());
            }
        }
    }
}
