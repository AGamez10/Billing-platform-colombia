package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.application.PlanChangeService;
import co.billingplatform.subscriptions.domain.PlanChangeRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPlanChangeRequests implements PlanChangeService.Requests {

    private final JdbcClient jdbc;

    JdbcPlanChangeRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replacePending(PlanChangeRequest r) {
        jdbc.sql("UPDATE plan_change_requests SET status = 'CANCELLED' WHERE tenant_id = :t AND status = 'PENDING'")
                .param("t", r.tenantId().value())
                .update();
        jdbc.sql("""
                        INSERT INTO plan_change_requests (tenant_id, id, from_plan, to_plan, cycle, quoted_price_cop,
                                                          requested_by, created_at)
                        VALUES (:t, :id, :from, :to, :cycle, :price, :by, :at)
                        """)
                .param("t", r.tenantId().value())
                .param("id", r.id())
                .param("from", r.fromPlan())
                .param("to", r.toPlan())
                .param("cycle", r.cycle().name())
                .param("price", r.quotedPrice())
                .param("by", r.requestedBy())
                .param("at", Timestamp.from(r.createdAt()))
                .update();
    }

    @Override
    public Optional<PlanChangeRequest> pending(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM plan_change_requests WHERE tenant_id = :t AND status = 'PENDING'")
                .param("t", tenantId.value())
                .query((rs, n) -> new PlanChangeRequest(rs.getObject("id", UUID.class),
                        new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getString("from_plan"),
                        rs.getString("to_plan"), PlanChangeRequest.Cycle.valueOf(rs.getString("cycle")),
                        rs.getBigDecimal("quoted_price_cop"), rs.getObject("requested_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
