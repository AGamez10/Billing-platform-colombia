package co.billingplatform.electronicinvoicing.adapter.persistence;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProviderAccountRepository implements ProviderAccountRepository {

    private final JdbcClient jdbc;

    JdbcProviderAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderAccount> findActive(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM provider_accounts WHERE tenant_id = :t AND active")
                .param("t", tenantId.value())
                .query(JdbcProviderAccountRepository::map)
                .optional();
    }

    @Override
    public Optional<ProviderAccount> findById(TenantId tenantId, UUID accountId) {
        return jdbc.sql("SELECT * FROM provider_accounts WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId.value())
                .param("id", accountId)
                .query(JdbcProviderAccountRepository::map)
                .optional();
    }

    @Override
    public Optional<ProviderAccount> findByWebhookToken(String webhookToken) {
        return jdbc.sql("SELECT * FROM webhook_find_account(:h)")
                .param("h", sha256(webhookToken))
                .query((rs, n) -> new ProviderAccount(rs.getObject("id", UUID.class),
                        new TenantId(rs.getObject("tenant_id", UUID.class)), new ProviderId(rs.getString("provider_id")),
                        ProviderEnvironment.valueOf(rs.getString("environment")),
                        rs.getString("credentials_secret_ref"), rs.getString("webhook_secret_ref")))
                .optional();
    }

    @Override
    public void activate(ProviderAccount account) {
        jdbc.sql("UPDATE provider_accounts SET active = false WHERE tenant_id = :t AND active")
                .param("t", account.tenantId().value())
                .update();
        jdbc.sql("""
                        INSERT INTO provider_accounts (tenant_id, id, provider_id, environment, credentials_secret_ref,
                                                       webhook_secret_ref, active)
                        VALUES (:t, :id, :p, :env, :cred, :hook, true)
                        """)
                .param("t", account.tenantId().value())
                .param("id", account.id())
                .param("p", account.providerId().value())
                .param("env", account.environment().name())
                .param("cred", account.credentialsSecretRef())
                .param("hook", account.webhookSecretRef())
                .update();
    }

    @Override
    public List<ProviderAccount> list(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM provider_accounts WHERE tenant_id = :t ORDER BY created_at DESC, id")
                .param("t", tenantId.value())
                .query(JdbcProviderAccountRepository::map)
                .list();
    }

    private static ProviderAccount map(ResultSet rs, int n) throws SQLException {
        return new ProviderAccount(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new ProviderId(rs.getString("provider_id")),
                ProviderEnvironment.valueOf(rs.getString("environment")),
                rs.getString("credentials_secret_ref"),
                rs.getString("webhook_secret_ref"));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
