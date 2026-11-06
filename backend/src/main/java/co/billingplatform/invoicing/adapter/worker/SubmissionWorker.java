package co.billingplatform.invoicing.adapter.worker;

import co.billingplatform.invoicing.adapter.persistence.JdbcSubmissionQueue;
import co.billingplatform.invoicing.adapter.persistence.JdbcSubmissionQueue.ClaimedJob;
import co.billingplatform.invoicing.application.SubmitInvoiceService;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Procesa la cola de envíos (outbox). Cada job se procesa en su propia transacción con el
 * tenant del job fijado para RLS; un fallo en un tenant no afecta a los demás.
 *
 * <ul>
 *   <li>Factura en estado final → job DONE.</li>
 *   <li>Sin estado final (pendiente, falla transitoria) → reintento con espera exponencial.</li>
 *   <li>Error de negocio (p. ej. {@code RECONCILIATION_REQUIRED}) → job DEAD: requiere a una persona.</li>
 * </ul>
 *
 * <p>Limitación conocida (piloto): la llamada al proveedor ocurre dentro de la transacción que
 * bloquea la factura. Con el volumen del piloto es aceptable; se separará antes de escalar.
 */
@Component
@ConditionalOnProperty(name = "billing.submission.worker-enabled", havingValue = "true", matchIfMissing = true)
class SubmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(SubmissionWorker.class);
    static final int MAX_JOB_ATTEMPTS = 25;

    private final JdbcSubmissionQueue queue;
    private final SubmitInvoiceService submissions;
    private final TenantTransactions tx;
    private final Clock clock;
    private final int batchSize;

    SubmissionWorker(JdbcSubmissionQueue queue, SubmitInvoiceService submissions, TenantTransactions tx, Clock clock,
                     @Value("${billing.submission.batch-size:10}") int batchSize) {
        this.queue = queue;
        this.submissions = submissions;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${billing.submission.poll-interval:2s}", initialDelayString = "${billing.submission.initial-delay:5s}")
    void poll() {
        List<ClaimedJob> jobs;
        try {
            jobs = tx.withoutTenant(() -> queue.claim(batchSize, 120));
        } catch (RuntimeException e) {
            log.warn("Could not claim submission jobs: {}", e.toString());
            return;
        }
        jobs.forEach(this::process);
    }

    void process(ClaimedJob job) {
        try {
            tx.inTenant(job.tenantId(), () -> {
                Invoice invoice = submissions.submit(job.tenantId(), job.invoiceId());
                if (invoice.status().isTerminal()) {
                    queue.complete(job.tenantId(), job.jobId());
                } else if (job.attempts() >= MAX_JOB_ATTEMPTS) {
                    queue.dead(job.tenantId(), job.jobId(), "max attempts reached: " + invoice.lastError().orElse("pending"));
                } else {
                    queue.retryAt(job.tenantId(), job.jobId(), nextAttempt(job.attempts()),
                            invoice.lastError().orElse("awaiting final result"));
                }
                return null;
            });
        } catch (DomainException e) {
            log.warn("Submission job {} for invoice {} needs attention: {}", job.jobId(), job.invoiceId(), e.code());
            tx.inTenant(job.tenantId(), () -> {
                queue.dead(job.tenantId(), job.jobId(), e.code() + ": " + e.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Submission job {} for invoice {} failed", job.jobId(), job.invoiceId(), e);
            tx.inTenant(job.tenantId(), () -> {
                if (job.attempts() >= MAX_JOB_ATTEMPTS) {
                    queue.dead(job.tenantId(), job.jobId(), e.getClass().getSimpleName());
                } else {
                    queue.retryAt(job.tenantId(), job.jobId(), nextAttempt(job.attempts()), e.getClass().getSimpleName());
                }
                return null;
            });
        }
    }

    /** 10 s, 20 s, 40 s… hasta 30 min. */
    Instant nextAttempt(int attempts) {
        long seconds = Math.min(10L << Math.min(Math.max(attempts - 1, 0), 10), Duration.ofMinutes(30).toSeconds());
        return clock.instant().plusSeconds(seconds);
    }
}
