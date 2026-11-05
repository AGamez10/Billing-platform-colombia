package co.billingplatform.invoicing.application;

import co.billingplatform.shared.domain.TenantId;

import java.util.UUID;

/**
 * Outbox de envíos: se escribe en la MISMA transacción que finaliza la factura (tabla
 * {@code submission_jobs}). Encolar dos veces la misma factura es idempotente.
 */
public interface SubmissionQueue {

    void enqueue(TenantId tenantId, UUID invoiceId);
}
