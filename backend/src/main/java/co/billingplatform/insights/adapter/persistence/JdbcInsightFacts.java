package co.billingplatform.insights.adapter.persistence;

import co.billingplatform.insights.application.InsightsService;
import co.billingplatform.insights.domain.AttentionRules;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Modelo de lectura del inicio: consultas agregadas sobre tablas de otros módulos (solo lectura,
 * dentro del tenant con RLS). La cuota la calcula el módulo de suscripciones (inyectada).
 */
@Repository
public class JdbcInsightFacts implements InsightsService.FactsReader {

    private final JdbcClient jdbc;
    private final InsightsService.QuotaReader quota;

    JdbcInsightFacts(JdbcClient jdbc, InsightsService.QuotaReader quota) {
        this.jdbc = jdbc;
        this.quota = quota;
    }

    @Override
    public AttentionRules.Facts facts(TenantId tenantId, LocalDate today, boolean includePlan) {
        var t = tenantId.value();
        String env = jdbc.sql("SELECT environment FROM provider_accounts WHERE tenant_id = :t AND active")
                .param("t", t).query(String.class).optional().orElse(null);
        boolean issuerReady = jdbc.sql("""
                        SELECT person_type IS NOT NULL AND address_line IS NOT NULL AND email IS NOT NULL
                        FROM organizations WHERE id = :t""")
                .param("t", t).query(Boolean.class).optional().orElse(false);
        List<AttentionRules.RangeFact> ranges = env == null ? List.of() : jdbc.sql("""
                        SELECT document_kind, prefix, range_to - range_from + 1 AS size, range_to - next_number + 1 AS remaining, valid_to
                        FROM numbering_ranges WHERE tenant_id = :t AND environment = :env AND active
                        """)
                .param("t", t).param("env", env)
                .query((rs, n) -> new AttentionRules.RangeFact(rs.getString("document_kind"), rs.getString("prefix"),
                        rs.getLong("size"), rs.getLong("remaining"), rs.getDate("valid_to").toLocalDate()))
                .list();
        Integer limit = null;
        int used = 0;
        boolean trial = false;
        String status = null;
        LocalDate periodEnd = null;
        if (includePlan) {
            InsightsService.Quota q = quota.quota(tenantId);
            limit = q.limit();
            used = q.used();
            trial = q.trial();
            Optional<Object[]> sub = jdbc.sql("""
                            SELECT status, current_period_end FROM subscriptions
                            WHERE tenant_id = :t AND status IN ('TRIAL', 'ACTIVE', 'PAST_DUE')""")
                    .param("t", t)
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getDate(2).toLocalDate()})
                    .optional();
            if (sub.isPresent()) {
                status = (String) sub.get()[0];
                periodEnd = (LocalDate) sub.get()[1];
            }
        }
        var counts = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'REJECTED' AND updated_at > now() - interval '30 days') AS rejected,
                               count(*) FILTER (WHERE status = 'FAILED' AND updated_at > now() - interval '30 days') AS failed,
                               count(*) FILTER (WHERE outcome_uncertain AND status IN ('PENDING_SUBMISSION', 'SUBMITTED')) AS uncertain,
                               count(*) FILTER (WHERE status = 'DRAFT' AND created_at < now() - interval '1 day') AS drafts
                        FROM invoices WHERE tenant_id = :t""")
                .param("t", t)
                .query((rs, n) -> new int[] {rs.getInt("rejected"), rs.getInt("failed"), rs.getInt("uncertain"), rs.getInt("drafts")})
                .single();
        return new AttentionRules.Facts(env, issuerReady, ranges, limit, used, trial, status, periodEnd,
                counts[0], counts[1], counts[2], counts[3]);
    }

    @Override
    public List<InsightsService.DailyTotal> dailySales(TenantId tenantId, String environment, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT d::date AS day,
                               coalesce(sum(CASE WHEN i.document_kind = 'CREDIT_NOTE' THEN -i.payable_amount ELSE i.payable_amount END), 0) AS total
                        FROM generate_series(CAST(:from AS date), CAST(:to AS date), interval '1 day') d
                        LEFT JOIN invoices i ON i.tenant_id = :t AND i.issue_date = d::date AND i.status = 'ACCEPTED'
                                             AND i.validation_environment = :env
                        GROUP BY d ORDER BY d
                        """)
                .param("t", tenantId.value()).param("env", environment)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new InsightsService.DailyTotal(rs.getDate("day").toLocalDate(), rs.getBigDecimal("total")))
                .list();
    }

    @Override
    public int acceptedSalesDocuments(TenantId tenantId, String environment, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT count(*) FROM invoices
                        WHERE tenant_id = :t AND status = 'ACCEPTED' AND validation_environment = :env
                          AND document_kind IN ('INVOICE', 'POS_EQUIVALENT') AND issue_date BETWEEN :from AND :to""")
                .param("t", tenantId.value()).param("env", environment)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(Integer.class).single();
    }

    @Override
    public BigDecimal credited(TenantId tenantId, String environment, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT coalesce(sum(payable_amount), 0) FROM invoices
                        WHERE tenant_id = :t AND status = 'ACCEPTED' AND validation_environment = :env
                          AND document_kind = 'CREDIT_NOTE' AND issue_date BETWEEN :from AND :to""")
                .param("t", tenantId.value()).param("env", environment)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(BigDecimal.class).single();
    }
}
