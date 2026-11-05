package co.billingplatform.shared.application;

import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.TenantId;

import java.util.Map;
import java.util.Objects;

/**
 * Registro de operaciones críticas (tabla {@code audit_log}, solo inserción). Se escribe en
 * la misma transacción que la operación auditada. Los detalles nunca incluyen secretos,
 * contraseñas ni tokens.
 */
public interface AuditTrail {

    void record(Event event);

    record Event(TenantId tenantId, Actor actor, String action, String resourceType, String resourceId,
                 Map<String, String> details) {
        public Event {
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(action);
            Objects.requireNonNull(resourceType);
            details = Map.copyOf(details == null ? Map.of() : details);
        }
    }
}
