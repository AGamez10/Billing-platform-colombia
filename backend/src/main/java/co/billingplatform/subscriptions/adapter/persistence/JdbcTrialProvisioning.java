package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Prueba gratis: 20 documentos en total, sin límite de tiempo (ADR-0014). Se registra con un
 * periodo de 100 años porque la cuota de la prueba es TOTAL y no por ciclo.
 */
@Repository
class JdbcTrialProvisioning implements IdentityPorts.TrialProvisioning {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcTrialProvisioning(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void startTrial(TenantId tenantId) {
        // La organización recién creada pasa a ser el tenant de esta transacción.
        jdbc.sql("SELECT set_config('app.tenant_id', :t, true)").param("t", tenantId.toString()).query(String.class).single();
        LocalDate today = ColombianTime.today(clock);
        jdbc.sql("""
                        INSERT INTO subscriptions (tenant_id, id, plan_code, status, current_period_start,
                                                   current_period_end, billing_interval)
                        VALUES (:t, :id, 'TRIAL', 'TRIAL', :start, :end, 'NONE')
                        """)
                .param("t", tenantId.value())
                .param("id", UUID.randomUUID())
                .param("start", Date.valueOf(today))
                .param("end", Date.valueOf(today.plusYears(100)))
                .update();
    }
}
