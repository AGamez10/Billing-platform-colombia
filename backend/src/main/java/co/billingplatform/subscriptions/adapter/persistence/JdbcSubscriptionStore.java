package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.application.OverageConsentRepository;
import co.billingplatform.subscriptions.application.SubscriptionRepository;
import co.billingplatform.subscriptions.application.UsageRepository;
import co.billingplatform.subscriptions.domain.OverageConsent;
import co.billingplatform.subscriptions.domain.QuotaWindow;
import co.billingplatform.subscriptions.domain.Subscription;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/** Suscripciones, consumo y aceptaciones de excedente (tablas de V5/V6). */
@Repository
class JdbcSubscriptionStore implements SubscriptionRepository, UsageRepository, OverageConsentRepository {

    private static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");

    private final JdbcClient jdbc;

    JdbcSubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Subscription> lockCurrent(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT s.id, s.plan_code, s.status, s.current_period_start, s.current_period_end,
                               s.override_quota_documents, s.override_max_users,
                               p.quota_scope, p.quota_documents, p.max_users, p.overage_unit_price_cop
                        FROM subscriptions s JOIN plans p ON p.code = s.plan_code
                        WHERE s.tenant_id = :t AND s.status IN ('TRIAL', 'ACTIVE', 'PAST_DUE')
                        FOR UPDATE OF s
                        """)
                .param("t", tenantId.value())
                .query((rs, n) -> new Subscription(
                        rs.getObject("id", UUID.class),
                        tenantId,
                        rs.getString("plan_code"),
                        Subscription.Status.valueOf(rs.getString("status")),
                        rs.getDate("current_period_start").toLocalDate(),
                        rs.getDate("current_period_end").toLocalDate(),
                        Subscription.QuotaScope.valueOf(rs.getString("quota_scope")),
                        new Subscription.Limits(rs.getObject("quota_documents", Integer.class),
                                rs.getObject("max_users", Integer.class)),
                        new Subscription.Limits(rs.getObject("override_quota_documents", Integer.class),
                                rs.getObject("override_max_users", Integer.class)),
                        rs.getBigDecimal("overage_unit_price_cop")))
                .optional();
    }

    @Override
    public long countIssued(TenantId tenantId, LocalDate from, LocalDate toExclusive) {
        return jdbc.sql("""
                        SELECT count(*) FROM usage_events
                        WHERE tenant_id = :t AND kind = 'DOCUMENT_ISSUED' AND environment = 'PRODUCTION'
                          AND occurred_at >= :from AND occurred_at < :to
                        """)
                .param("t", tenantId.value())
                .param("from", startOfDay(from))
                .param("to", startOfDay(toExclusive))
                .query(Long.class)
                .single();
    }

    @Override
    public void recordIssued(TenantId tenantId, UUID documentId, String providerId, String environment, Instant at,
                             BigDecimal overageUnitPrice) {
        jdbc.sql("""
                        INSERT INTO usage_events (tenant_id, id, kind, document_id, provider_id, environment,
                                                  occurred_at, overage_unit_price_cop)
                        VALUES (:t, :id, 'DOCUMENT_ISSUED', :doc, :p, :env, :at, :price)
                        ON CONFLICT ON CONSTRAINT usage_events_once DO NOTHING
                        """)
                .param("t", tenantId.value())
                .param("id", UUID.randomUUID())
                .param("doc", documentId)
                .param("p", providerId)
                .param("env", environment)
                .param("at", Timestamp.from(at))
                .param("price", overageUnitPrice)
                .update();
    }

    @Override
    public Optional<OverageConsent> find(TenantId tenantId, UUID subscriptionId, LocalDate windowStart) {
        return jdbc.sql("""
                        SELECT window_start, window_end, unit_price_cop, accepted_by_user, accepted_by_api_key, accepted_at
                        FROM overage_consents WHERE tenant_id = :t AND subscription_id = :s AND window_start = :w
                        """)
                .param("t", tenantId.value())
                .param("s", subscriptionId)
                .param("w", Date.valueOf(windowStart))
                .query((rs, n) -> new OverageConsent(tenantId, subscriptionId,
                        new QuotaWindow(rs.getDate("window_start").toLocalDate(), rs.getDate("window_end").toLocalDate()),
                        rs.getBigDecimal("unit_price_cop"),
                        new Actor(rs.getObject("accepted_by_user", UUID.class), rs.getObject("accepted_by_api_key", UUID.class)),
                        rs.getTimestamp("accepted_at").toInstant()))
                .optional();
    }

    @Override
    public void save(OverageConsent c) {
        jdbc.sql("""
                        INSERT INTO overage_consents (tenant_id, subscription_id, window_start, window_end, unit_price_cop,
                                                      accepted_by_user, accepted_by_api_key, accepted_at)
                        VALUES (:t, :s, :ws, :we, :price, :u, :k, :at)
                        ON CONFLICT DO NOTHING
                        """)
                .param("t", c.tenantId().value())
                .param("s", c.subscriptionId())
                .param("ws", Date.valueOf(c.window().start()))
                .param("we", Date.valueOf(c.window().endExclusive()))
                .param("price", c.unitPrice())
                .param("u", c.acceptedBy().userId())
                .param("k", c.acceptedBy().apiKeyId())
                .param("at", Timestamp.from(c.acceptedAt()))
                .update();
    }

    private static Timestamp startOfDay(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(COLOMBIA).toInstant());
    }
}
