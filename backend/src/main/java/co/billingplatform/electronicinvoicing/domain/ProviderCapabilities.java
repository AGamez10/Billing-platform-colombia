package co.billingplatform.electronicinvoicing.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Lo que un proveedor realmente soporta. Los proveedores no son equivalentes: la
 * aplicación consulta estas capacidades en vez de asumirlas, y nunca simula una
 * capacidad ausente.
 *
 * @param idempotentSubmission     el proveedor deduplica envíos con la misma clave
 * @param statusQuery              permite consultar el estado por referencia externa
 * @param lookupByDocumentNumber   permite buscar un documento por prefijo+número propio
 *                                 (necesario para reconciliar envíos con resultado incierto
 *                                 cuando no hay idempotencia)
 * @param webhooks                 notifica cambios de estado por webhook
 */
public record ProviderCapabilities(
        Set<DocumentKind> documentKinds,
        Set<ProviderEnvironment> environments,
        boolean idempotentSubmission,
        boolean statusQuery,
        boolean lookupByDocumentNumber,
        boolean webhooks) {

    public ProviderCapabilities {
        documentKinds = Set.copyOf(Objects.requireNonNull(documentKinds));
        environments = Set.copyOf(Objects.requireNonNull(environments));
    }

    public boolean supports(DocumentKind kind) {
        return documentKinds.contains(kind);
    }

    public boolean supports(ProviderEnvironment environment) {
        return environments.contains(environment);
    }

    /** Puede reintentarse un envío de resultado incierto sin riesgo de duplicar el documento. */
    public boolean canSafelyRetryUncertainSubmission() {
        return idempotentSubmission || lookupByDocumentNumber;
    }
}
