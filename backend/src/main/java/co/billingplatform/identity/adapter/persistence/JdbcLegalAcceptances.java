package co.billingplatform.identity.adapter.persistence;

import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class JdbcLegalAcceptances implements IdentityPorts.LegalAcceptances {

    private final JdbcClient jdbc;

    JdbcLegalAcceptances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(TenantId tenantId, UUID userId, String document, String version) {
        // En el registro aún no hay tenant en contexto: la organización recién creada pasa a serlo.
        jdbc.sql("SELECT set_config('app.tenant_id', :t, true)").param("t", tenantId.toString()).query(String.class).single();
        jdbc.sql("""
                        INSERT INTO legal_acceptances (tenant_id, id, user_id, document, version)
                        VALUES (:t, :id, :u, :doc, :v)
                        """)
                .param("t", tenantId.value())
                .param("id", UUID.randomUUID())
                .param("u", userId)
                .param("doc", document)
                .param("v", version)
                .update();
    }
}
