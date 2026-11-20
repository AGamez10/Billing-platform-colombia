package co.billingplatform.insights.adapter.persistence;

import co.billingplatform.insights.application.AlertNotifier;
import co.billingplatform.insights.domain.Attention;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcAlertLedger implements AlertNotifier.Ledger {

    private final JdbcClient jdbc;

    JdbcAlertLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> alreadySent(TenantId tenantId, List<String> keys) {
        if (keys.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("SELECT alert_key FROM alert_notifications WHERE tenant_id = :t AND alert_key IN (:keys)")
                .param("t", tenantId.value()).param("keys", keys)
                .query(String.class).list());
    }

    @Override
    public void record(TenantId tenantId, Attention a, int recipients) {
        jdbc.sql("""
                        INSERT INTO alert_notifications (tenant_id, alert_key, code, recipients) VALUES (:t, :k, :c, :r)
                        ON CONFLICT (tenant_id, alert_key) DO NOTHING""")
                .param("t", tenantId.value()).param("k", a.key()).param("c", a.code()).param("r", recipients)
                .update();
    }

    @Override
    public List<String> recipients(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT u.email FROM memberships m JOIN users u ON u.id = m.user_id
                        WHERE m.tenant_id = :t AND m.status = 'ACTIVE' AND m.role IN ('OWNER', 'ADMIN')
                        ORDER BY u.email""")
                .param("t", tenantId.value()).query(String.class).list();
    }

    @Override
    public String organizationName(TenantId tenantId) {
        return jdbc.sql("SELECT coalesce(trade_name, legal_name) FROM organizations WHERE id = :t")
                .param("t", tenantId.value()).query(String.class).single();
    }

    /** Sin tenant: organizaciones activas con avisos encendidos. */
    public List<TenantId> organizations() {
        return jdbc.sql("SELECT tenant_id FROM organizations_with_email_alerts()")
                .query((rs, n) -> new TenantId(rs.getObject(1, UUID.class))).list();
    }
}
