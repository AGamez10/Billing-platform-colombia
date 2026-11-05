package co.billingplatform.shared.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador de la organización (tenant) dueña de un recurso.
 *
 * <p>Nunca se construye a partir de datos enviados por el cliente HTTP: la capa web
 * lo obtiene de la identidad autenticada (ver docs/MULTITENANCY.md). Todo puerto de
 * repositorio de un recurso de tenant lo exige como parámetro obligatorio.
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "tenant id is required");
    }

    public static TenantId of(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
