package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Tabla {@code usage_events}. */
public interface UsageRepository {

    /** Documentos emitidos en PRODUCTION con fecha (hora de Colombia) en [from, toExclusive). */
    long countIssued(TenantId tenantId, LocalDate from, LocalDate toExclusive);

    /**
     * Inserta si no existe (restricción única por tenant, tipo y documento).
     *
     * @param overageUnitPrice precio a cobrar por ser excedente aceptado; {@code null} si está dentro del plan
     */
    void recordIssued(TenantId tenantId, UUID documentId, String providerId, String environment, Instant at,
                      BigDecimal overageUnitPrice);
}
