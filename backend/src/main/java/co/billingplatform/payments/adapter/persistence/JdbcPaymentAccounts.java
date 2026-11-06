package co.billingplatform.payments.adapter.persistence;

import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPaymentAccounts implements PaymentPorts.PaymentAccounts {

    private final JdbcClient jdbc;

    JdbcPaymentAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void activate(PaymentAccount a, byte[] webhookTokenHash) {
        jdbc.sql("UPDATE payment_accounts SET active = false WHERE tenant_id = :t AND active")
                .param("t", a.tenantId().value())
                .update();
        jdbc.sql("""
                        INSERT INTO payment_accounts (tenant_id, id, gateway, mode, identity_key_ref, secret_key_ref,
                                                      webhook_token_hash, active)
                        VALUES (:t, :id, :g, :m, :ik, :sk, :h, true)
                        """)
                .param("t", a.tenantId().value())
                .param("id", a.id())
                .param("g", a.gateway().name())
                .param("m", a.mode().name())
                .param("ik", a.identityKeyRef())
                .param("sk", a.secretKeyRef())
                .param("h", webhookTokenHash)
                .update();
    }

    @Override
    public Optional<PaymentAccount> findActive(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM payment_accounts WHERE tenant_id = :t AND active")
                .param("t", tenantId.value())
                .query(JdbcPaymentAccounts::map)
                .optional();
    }

    @Override
    public Optional<PaymentAccount> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM payment_accounts WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId.value())
                .param("id", id)
                .query(JdbcPaymentAccounts::map)
                .optional();
    }

    @Override
    public Optional<PaymentAccount> findByWebhookToken(String token) {
        if (token == null || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM payment_webhook_find_account(:h)")
                .param("h", sha256(token))
                .query(JdbcPaymentAccounts::map)
                .optional();
    }

    private static PaymentAccount map(ResultSet rs, int n) throws SQLException {
        return new PaymentAccount(rs.getObject("id", UUID.class), new TenantId(rs.getObject("tenant_id", UUID.class)),
                PaymentAccount.Gateway.valueOf(rs.getString("gateway")), PaymentAccount.Mode.valueOf(rs.getString("mode")),
                rs.getString("identity_key_ref"), rs.getString("secret_key_ref"));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
