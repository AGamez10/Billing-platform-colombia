package co.billingplatform.electronicinvoicing.domain;

import java.util.UUID;

/**
 * Clave de idempotencia de envío. Es estable por documento (no por intento): reintentar
 * con la misma clave nunca debe crear un segundo documento en un proveedor que la soporte.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
    }

    public static IdempotencyKey forDocument(DocumentKind kind, UUID documentId) {
        return new IdempotencyKey(kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + "-" + documentId);
    }
}
