package co.billingplatform.subscriptions.domain;

import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitud de cambio de plan hecha desde la oferta de mejora. El plan NO cambia aquí: se activa
 * cuando se confirma el pago (hoy, link de Mercado Pago enviado por el equipo; ADR-0021).
 *
 * @param quotedPrice precio mostrado al pedirlo (COP), por ciclo; queda registrado como prueba de la oferta
 */
public record PlanChangeRequest(UUID id, TenantId tenantId, String fromPlan, String toPlan, Cycle cycle,
                                BigDecimal quotedPrice, UUID requestedBy, Instant createdAt) {

    public enum Cycle { MONTHLY, ANNUAL }

    public PlanChangeRequest {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(fromPlan);
        Objects.requireNonNull(toPlan);
        Objects.requireNonNull(cycle);
        Objects.requireNonNull(requestedBy);
        Objects.requireNonNull(createdAt);
        if (quotedPrice == null || quotedPrice.signum() <= 0) {
            throw new IllegalArgumentException("quoted price must be positive");
        }
    }
}
