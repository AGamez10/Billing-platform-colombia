package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Tabla {@code usage_events}. */
public interface UsageRepository {

    /** Documentos emitidos en PRODUCTION con fecha (hora de Colombia) en [from, toExclusive). */
    long countIssued(TenantId tenantId, LocalDate from, LocalDate toExclusive);

    /** Inserta si no existe (restricción única por tenant, tipo y documento). */
    void recordIssued(TenantId tenantId, UUID documentId, String providerId, String environment, Instant at);
}
