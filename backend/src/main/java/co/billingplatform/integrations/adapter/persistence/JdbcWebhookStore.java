package co.billingplatform.integrations.adapter.persistence;

import co.billingplatform.integrations.application.WebhookService;
import co.billingplatform.integrations.domain.WebhookEventType;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcWebhookStore implements WebhookService.Store {

    public record Claimed(TenantId tenantId, UUID id) {}

    private final JdbcClient jdbc;

    JdbcWebhookStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WebhookService.Endpoint> endpoints(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE tenant_id = :t ORDER BY created_at DESC")
                .param("t", tenantId.value()).query(JdbcWebhookStore::endpoint).list();
    }

    @Override
    public Optional<WebhookService.Endpoint> endpoint(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId.value()).param("id", id).query(JdbcWebhookStore::endpoint).optional();
    }

    @Override
    public void insert(WebhookService.Endpoint e) {
        jdbc.sql("""
                        INSERT INTO webhook_endpoints (tenant_id, id, url, events, secret_ref, description, active, created_at)
                        VALUES (:t, :id, :url, :events, :ref, :d, true, :at)""")
                .param("t", e.tenantId().value()).param("id", e.id()).param("url", e.url())
                .param("events", e.events().stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("ref", e.secretRef()).param("d", e.description()).param("at", Timestamp.from(e.createdAt()))
                .update();
    }

    @Override
    public void disable(TenantId tenantId, UUID id) {
        jdbc.sql("UPDATE webhook_endpoints SET active = false, disabled_at = now() WHERE tenant_id = :t AND id = :id AND active")
                .param("t", tenantId.value()).param("id", id).update();
    }

    @Override
    public void enqueue(WebhookService.Delivery d) {
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (tenant_id, id, endpoint_id, event_type, payload, created_at, next_attempt_at)
                        VALUES (:t, :id, :e, :type, CAST(:payload AS jsonb), :at, :at)""")
                .param("t", d.tenantId().value()).param("id", d.id()).param("e", d.endpointId()).param("type", d.event().name())
                .param("payload", d.payload()).param("at", Timestamp.from(d.createdAt()))
                .update();
    }

    @Override
    public Optional<WebhookService.Delivery> delivery(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT *, payload::text AS body FROM webhook_deliveries WHERE tenant_id = :t AND id = :id FOR UPDATE")
                .param("t", tenantId.value()).param("id", id).query(JdbcWebhookStore::delivery).optional();
    }

    @Override
    public void recordAttempt(TenantId tenantId, UUID id, String status, int attempts, Instant next, Integer lastStatus,
                              String lastError, Instant deliveredAt) {
        jdbc.sql("""
                        UPDATE webhook_deliveries
                        SET status = :s, attempts = :a, next_attempt_at = :n, leased_until = NULL, last_status = :ls,
                            last_error = :le, delivered_at = :da
                        WHERE tenant_id = :t AND id = :id""")
                .param("s", status).param("a", attempts).param("n", Timestamp.from(next)).param("ls", lastStatus)
                .param("le", lastError).param("da", deliveredAt == null ? null : Timestamp.from(deliveredAt))
                .param("t", tenantId.value()).param("id", id)
                .update();
    }

    @Override
    public List<WebhookService.Delivery> recentDeliveries(TenantId tenantId, UUID endpointId, int limit) {
        return jdbc.sql("""
                        SELECT *, payload::text AS body FROM webhook_deliveries
                        WHERE tenant_id = :t AND endpoint_id = :e ORDER BY created_at DESC LIMIT :l""")
                .param("t", tenantId.value()).param("e", endpointId).param("l", limit)
                .query(JdbcWebhookStore::delivery).list();
    }

    /** Sin tenant: entregas vencidas, con arriendo. */
    public List<Claimed> claim(int limit) {
        return jdbc.sql("SELECT tenant_id, id FROM claim_webhook_deliveries(:l)").param("l", limit)
                .query((rs, n) -> new Claimed(new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class)))
                .list();
    }

    private static WebhookService.Endpoint endpoint(ResultSet rs, int n) throws SQLException {
        Set<WebhookEventType> events = EnumSet.noneOf(WebhookEventType.class);
        Arrays.stream((String[]) rs.getArray("events").getArray()).map(WebhookEventType::valueOf).forEach(events::add);
        return new WebhookService.Endpoint(new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class),
                rs.getString("url"), Set.copyOf(events), rs.getString("secret_ref"), rs.getString("description"),
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
    }

    private static WebhookService.Delivery delivery(ResultSet rs, int n) throws SQLException {
        Object last = rs.getObject("last_status");
        return new WebhookService.Delivery(new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class),
                rs.getObject("endpoint_id", UUID.class), WebhookEventType.valueOf(rs.getString("event_type")), rs.getString("body"),
                rs.getString("status"), rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(),
                last == null ? null : ((Number) last).intValue(), rs.getString("last_error"), rs.getTimestamp("created_at").toInstant());
    }
}
