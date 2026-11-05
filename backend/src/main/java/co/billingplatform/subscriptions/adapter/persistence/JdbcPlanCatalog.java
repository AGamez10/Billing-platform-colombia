package co.billingplatform.subscriptions.adapter.persistence;

import co.billingplatform.subscriptions.application.PlanCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Catálogo global (sin tenant): la tabla {@code plans} no tiene datos de organizaciones. */
@Repository
class JdbcPlanCatalog implements PlanCatalog {

    private final JdbcClient jdbc;

    JdbcPlanCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PublicPlan> publicPlans() {
        return jdbc.sql("""
                        SELECT code, name, quota_documents, quota_scope, max_users, monthly_price_cop,
                               monthly_billing_allowed, annual_price_cop, overage_unit_price_cop
                        FROM plans
                        WHERE public AND active
                        ORDER BY coalesce(monthly_price_cop, 0), code
                        """)
                .query((rs, i) -> new PublicPlan(
                        rs.getString("code"),
                        rs.getString("name"),
                        (Integer) rs.getObject("quota_documents"),
                        rs.getString("quota_scope"),
                        (Integer) rs.getObject("max_users"),
                        plain(rs.getBigDecimal("monthly_price_cop")),
                        rs.getBoolean("monthly_billing_allowed"),
                        plain(rs.getBigDecimal("annual_price_cop")),
                        plain(rs.getBigDecimal("overage_unit_price_cop"))))
                .list();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
