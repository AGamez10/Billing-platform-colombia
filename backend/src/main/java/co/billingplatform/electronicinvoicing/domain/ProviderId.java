package co.billingplatform.electronicinvoicing.domain;

import java.util.Objects;

/** Identificador estable de un adaptador de proveedor (p. ej. {@code mock}, {@code kiai}). */
public record ProviderId(String value) {

    public ProviderId {
        Objects.requireNonNull(value);
        if (!value.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("invalid provider id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
