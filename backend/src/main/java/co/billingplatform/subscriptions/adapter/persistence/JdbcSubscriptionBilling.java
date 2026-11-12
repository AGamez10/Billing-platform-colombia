package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts;
import co.billingplatform.subscriptions.domain.PlanChangeRequest;
import co.billingplatform.subscriptions.domain.SubscriptionCheckout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Checkouts de suscripción y cambios de vigencia (V15). */
@Repository
public class JdbcSubscriptionBilling implements SubscriptionBillingPorts.Checkouts, SubscriptionBillingPorts.Renewals {

    public record Claimed(TenantId tenantId, UUID id) {}

    private final JdbcClient jdbc;

    JdbcSubscriptionBilling(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SubscriptionCheckout c) {
        jdbc.sql("""
                        INSERT INTO subscription_checkouts (tenant_id, id, plan_code, cycle, amount, status, gateway, preference_id,
                                                            checkout_url, created_by, created_at, updated_at)
                        VALUES (:t, :id, :plan, :cycle, :amount, :status, 'MERCADOPAGO', :pref, :url, :by, :at, :at)
                        """)
                .param("t", c.tenantId().value())
                .param("id", c.id())
                .param("plan", c.planCode())
                .param("cycle", c.cycle().name())
                .param("amount", c.amount())
                .param("status", c.status().name())
                .param("pref", c.preferenceId().orElse(null))
                .param("url", c.checkoutUrl().orElse(null))
                .param("by", c.createdBy())
                .param("at", Timestamp.from(c.createdAt()))
                .update();
    }

    @Override
    public void update(SubscriptionCheckout c) {
        jdbc.sql("""
                        UPDATE subscription_checkouts
                        SET status = :status, gateway_payment_id = :payment, paid_at = :paid, updated_at = now()
                        WHERE tenant_id = :t AND id = :id
                        """)
                .param("status", c.status().name())
                .param("payment", c.gatewayPaymentId().orElse(null))
                .param("paid", c.paidAt().map(Timestamp::from).orElse(null))
                .param("t", c.tenantId().value())
                .param("id", c.id())
                .update();
    }

    @Override
    public Optional<SubscriptionCheckout> lock(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM subscription_checkouts WHERE tenant_id = :t AND id = :id FOR UPDATE")
                .param("t", tenantId.value())
                .param("id", id)
                .query(JdbcSubscriptionBilling::checkout)
                .optional();
    }

    @Override
    public Optional<TenantId> tenantOf(UUID id) {
        return jdbc.sql("SELECT subscription_checkout_tenant(:id)")
                .param("id", id)
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)).map(TenantId::new))
                .single();
    }

    @Override
    public void activate(TenantId tenantId, UUID subscriptionId, String planCode, LocalDate periodStart, LocalDate periodEnd) {
        jdbc.sql("""
                        UPDATE subscriptions
                        SET plan_code = :plan, status = 'ACTIVE', current_period_start = :start, current_period_end = :end
                        WHERE tenant_id = :t AND id = :id
                        """)
                .param("plan", planCode)
                .param("start", Date.valueOf(periodStart))
                .param("end", Date.valueOf(periodEnd))
                .param("t", tenantId.value())
                .param("id", subscriptionId)
                .update();
    }

    @Override
    public int markOverdue(LocalDate today, int graceDays) {
        return jdbc.sql("SELECT mark_overdue_subscriptions(:today, :grace)")
                .param("today", Date.valueOf(today))
                .param("grace", graceDays)
                .query(Integer.class)
                .single();
    }

    /** Sin tenant: checkouts pendientes para conciliar (con lease). */
    public List<Claimed> claimPending(int limit) {
        return jdbc.sql("SELECT tenant_id, id FROM claim_pending_checkouts(:limit)")
                .param("limit", limit)
                .query((rs, n) -> new Claimed(new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class)))
                .list();
    }

    private static SubscriptionCheckout checkout(ResultSet rs, int n) throws SQLException {
        return new SubscriptionCheckout(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                rs.getString("plan_code"),
                PlanChangeRequest.Cycle.valueOf(rs.getString("cycle")),
                rs.getBigDecimal("amount"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                SubscriptionCheckout.Status.valueOf(rs.getString("status")),
                rs.getString("preference_id"),
                rs.getString("checkout_url"),
                rs.getString("gateway_payment_id"),
                instant(rs, "paid_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
