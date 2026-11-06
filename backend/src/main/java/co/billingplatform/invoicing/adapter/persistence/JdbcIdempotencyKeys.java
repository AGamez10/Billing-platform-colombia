package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.application.PosSaleService;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Claves de idempotencia de la API ({@code api_idempotency_keys}, RLS por tenant, vencen a las 24 h). */
@Repository
class JdbcIdempotencyKeys implements PosSaleService.IdempotencyKeys {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Stored> reserve(TenantId tenantId, String key, byte[] requestHash) {
        jdbc.sql("DELETE FROM api_idempotency_keys WHERE tenant_id = :t AND idempotency_key = :k AND expires_at <= now()")
                .param("t", tenantId.value())
                .param("k", key)
                .update();
        // Si otra transacción reservó la misma clave y no ha terminado, este INSERT espera a que lo haga.
        int inserted = jdbc.sql("""
                        INSERT INTO api_idempotency_keys (tenant_id, idempotency_key, request_hash)
                        VALUES (:t, :k, :h)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """)
                .param("t", tenantId.value())
                .param("k", key)
                .param("h", requestHash)
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT request_hash, response_body->>'resourceId' AS resource_id
                        FROM api_idempotency_keys WHERE tenant_id = :t AND idempotency_key = :k
                        """)
                .param("t", tenantId.value())
                .param("k", key)
                .query((rs, n) -> {
                    String id = rs.getString("resource_id");
                    return new Stored(rs.getBytes("request_hash"), id == null ? null : UUID.fromString(id));
                })
                .optional();
    }

    @Override
    public void complete(TenantId tenantId, String key, UUID resourceId) {
        jdbc.sql("""
                        UPDATE api_idempotency_keys
                        SET response_status = 201, response_body = jsonb_build_object('resourceId', CAST(:r AS text))
                        WHERE tenant_id = :t AND idempotency_key = :k
                        """)
                .param("r", resourceId.toString())
                .param("t", tenantId.value())
                .param("k", key)
                .update();
    }
}
