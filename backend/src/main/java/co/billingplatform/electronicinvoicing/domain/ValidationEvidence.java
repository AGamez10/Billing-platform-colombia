package co.billingplatform.electronicinvoicing.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Evidencia de validación de un documento, tal como la reportó el proveedor.
 *
 * <p>Es la única forma de que un documento llegue al estado validado. Sin evidencia no
 * se afirma validación. La evidencia recuerda el ambiente: un documento validado en
 * {@link ProviderEnvironment#SIMULATION} o {@link ProviderEnvironment#HABILITATION}
 * nunca se presenta como factura electrónica con validez tributaria.
 *
 * @param documentKey código único del documento (CUFE/CUDE) informado por el proveedor
 * @param qrPayload   contenido del código QR de la representación gráfica tal como lo entrega el
 *                    proveedor ({@code null} si no lo informa); la plataforma no lo calcula
 */
public record ValidationEvidence(String documentKey, ProviderEnvironment environment, Instant validatedAt,
                                 String qrPayload) {

    public ValidationEvidence(String documentKey, ProviderEnvironment environment, Instant validatedAt) {
        this(documentKey, environment, validatedAt, null);
    }

    public ValidationEvidence {
        if (documentKey == null || documentKey.isBlank()) {
            throw new IllegalArgumentException("validation evidence requires a document key");
        }
        Objects.requireNonNull(environment);
        Objects.requireNonNull(validatedAt);
        qrPayload = qrPayload == null || qrPayload.isBlank() ? null : qrPayload;
        if (qrPayload != null && qrPayload.length() > 2000) {
            throw new IllegalArgumentException("QR payload is too long");
        }
    }
}
