package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.identity.application.AccountPorts;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Usuarios máximos del plan vigente (la excepción de la suscripción manda sobre el plan). */
@Repository
class JdbcSeatLimit implements AccountPorts.SeatLimit {

    private final JdbcClient jdbc;

    JdbcSeatLimit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Integer> maxUsers(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT COALESCE(s.override_max_users, p.max_users) AS max_users
                        FROM subscriptions s JOIN plans p ON p.code = s.plan_code
                        WHERE s.tenant_id = :t AND s.status IN ('TRIAL', 'ACTIVE', 'PAST_DUE')
                        """)
                .param("t", tenantId.value())
                .query((rs, n) -> Optional.ofNullable(rs.getObject("max_users", Integer.class)))
                .optional()
                .flatMap(o -> o);
    }
}
