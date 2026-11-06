package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.application.SubmissionQueue;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbox de envíos ({@code submission_jobs}, ADR-0006). */
@Repository
public class JdbcSubmissionQueue implements SubmissionQueue {

    public record ClaimedJob(TenantId tenantId, UUID jobId, UUID invoiceId, int attempts) {}

    private final JdbcClient jdbc;

    JdbcSubmissionQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(TenantId tenantId, UUID invoiceId) {
        jdbc.sql("""
                        INSERT INTO submission_jobs (tenant_id, id, invoice_id) VALUES (:t, :id, :inv)
                        ON CONFLICT (tenant_id, invoice_id) WHERE status = 'QUEUED' DO NOTHING
                        """)
                .param("t", tenantId.value())
                .param("id", UUID.randomUUID())
                .param("inv", invoiceId)
                .update();
    }

    /** Sin tenant en contexto: usa la función SECURITY DEFINER con SKIP LOCKED y lease. */
    public List<ClaimedJob> claim(int limit, int leaseSeconds) {
        return jdbc.sql("SELECT * FROM claim_submission_jobs(:l, :s)")
                .param("l", limit)
                .param("s", leaseSeconds)
                .query((rs, n) -> new ClaimedJob(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("job_id", UUID.class), rs.getObject("invoice_id", UUID.class), rs.getInt("attempts")))
                .list();
    }

    public void complete(TenantId tenantId, UUID jobId) {
        finish(tenantId, jobId, "DONE", null);
    }

    public void dead(TenantId tenantId, UUID jobId, String reason) {
        finish(tenantId, jobId, "DEAD", reason);
    }

    public void retryAt(TenantId tenantId, UUID jobId, Instant next, String reason) {
        jdbc.sql("""
                        UPDATE submission_jobs SET next_attempt_at = :next, locked_until = NULL, last_error = :reason
                        WHERE tenant_id = :t AND id = :id
                        """)
                .param("next", Timestamp.from(next))
                .param("reason", truncate(reason))
                .param("t", tenantId.value())
                .param("id", jobId)
                .update();
    }

    private void finish(TenantId tenantId, UUID jobId, String status, String reason) {
        jdbc.sql("""
                        UPDATE submission_jobs SET status = :s, locked_until = NULL, last_error = :reason
                        WHERE tenant_id = :t AND id = :id
                        """)
                .param("s", status)
                .param("reason", truncate(reason))
                .param("t", tenantId.value())
                .param("id", jobId)
                .update();
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
    }
}
