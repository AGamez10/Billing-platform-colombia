package co.billingplatform.payments.adapter.persistence;

import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCharges implements PaymentPorts.Charges {

    public record ClaimedCharge(TenantId tenantId, UUID id) {}

    private final JdbcClient jdbc;

    JdbcCharges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Charge c) {
        jdbc.sql("""
                        INSERT INTO charges (tenant_id, id, invoice_id, payment_account_id, created_by, amount, status,
                                             gateway_link_id, checkout_url)
                        VALUES (:t, :id, :inv, :acc, :by, :amount, :status, :link, :url)
                        """)
                .param("t", c.tenantId().value())
                .param("id", c.id())
                .param("inv", c.invoiceId())
                .param("acc", c.paymentAccountId())
                .param("by", c.createdBy())
                .param("amount", c.amount())
                .param("status", c.status().name())
                .param("link", c.gatewayLinkId().orElse(null))
                .param("url", c.checkoutUrl().orElse(null))
                .update();
    }

    @Override
    public void update(Charge c) {
        jdbc.sql("""
                        UPDATE charges SET status = :status, gateway_transaction_id = :tx, payment_method = :method,
                                           paid_at = :paid, updated_at = now()
                        WHERE tenant_id = :t AND id = :id
                        """)
                .param("status", c.status().name())
                .param("tx", c.gatewayTransactionId().orElse(null))
                .param("method", c.paymentMethod().orElse(null))
                .param("paid", c.paidAt().map(Timestamp::from).orElse(null))
                .param("t", c.tenantId().value())
                .param("id", c.id())
                .update();
    }

    @Override
    public Optional<Charge> lock(TenantId tenantId, UUID chargeId) {
        return jdbc.sql("SELECT * FROM charges WHERE tenant_id = :t AND id = :id FOR UPDATE")
                .param("t", tenantId.value())
                .param("id", chargeId)
                .query(JdbcCharges::map)
                .optional();
    }

    @Override
    public List<UUID> pendingSince(TenantId tenantId, Instant since) {
        return jdbc.sql("SELECT id FROM charges WHERE tenant_id = :t AND status = 'PENDING' AND created_at >= :since ORDER BY created_at LIMIT 50")
                .param("t", tenantId.value())
                .param("since", Timestamp.from(since))
                .query(UUID.class)
                .list();
    }

    /** Sin tenant: función SECURITY DEFINER con SKIP LOCKED y lease (conciliación periódica). */
    public List<ClaimedCharge> claimPending(int limit) {
        return jdbc.sql("SELECT * FROM claim_pending_charges(:l)")
                .param("l", limit)
                .query((rs, n) -> new ClaimedCharge(new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class)))
                .list();
    }

    private static Charge map(ResultSet rs, int n) throws SQLException {
        Timestamp paid = rs.getTimestamp("paid_at");
        return new Charge(rs.getObject("id", UUID.class), new TenantId(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("invoice_id", UUID.class), rs.getObject("payment_account_id", UUID.class),
                rs.getObject("created_by", UUID.class), rs.getBigDecimal("amount"),
                Charge.Status.valueOf(rs.getString("status")), rs.getString("gateway_link_id"), rs.getString("checkout_url"),
                rs.getString("gateway_transaction_id"), rs.getString("payment_method"), paid == null ? null : paid.toInstant());
    }
}
