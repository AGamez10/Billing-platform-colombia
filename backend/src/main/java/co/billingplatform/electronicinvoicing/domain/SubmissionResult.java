package co.billingplatform.electronicinvoicing.domain;

import java.util.List;
import java.util.Objects;

/**
 * Resultado normalizado de un envío o consulta. Cada adaptador traduce las respuestas
 * de su proveedor a uno de estos casos; ninguna respuesta cruda llega al dominio.
 */
public sealed interface SubmissionResult {

    /** El proveedor informó validación, con evidencia. */
    record Accepted(ExternalReference reference, ValidationEvidence evidence) implements SubmissionResult {
        public Accepted {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(evidence);
        }
    }

    /** Recibido por el proveedor; el resultado final llegará después. */
    record Pending(ExternalReference reference) implements SubmissionResult {
        public Pending {
            Objects.requireNonNull(reference);
        }
    }

    /** Rechazado por reglas de validación (del proveedor o de la DIAN). */
    record Rejected(ExternalReference reference, List<String> reasons) implements SubmissionResult {
        public Rejected {
            reasons = List.copyOf(reasons == null || reasons.isEmpty() ? List.of("Rejected without reason") : reasons);
        }
    }

    /**
     * Falla técnica que puede reintentarse.
     *
     * @param requestMayHaveBeenReceived {@code true} si no se puede descartar que el
     *        proveedor haya recibido el documento (p. ej. timeout de lectura). En ese caso
     *        reintentar a ciegas puede duplicar el documento.
     */
    record TransientFailure(String reason, boolean requestMayHaveBeenReceived) implements SubmissionResult {}

    /** Falla que no se resuelve reintentando (credenciales inválidas, contrato vencido...). */
    record PermanentFailure(String reason) implements SubmissionResult {}
}
