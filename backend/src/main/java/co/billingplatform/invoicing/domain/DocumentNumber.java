package co.billingplatform.invoicing.domain;

import java.util.Objects;

/** Número asignado de una resolución: prefijo + consecutivo. */
public record DocumentNumber(String prefix, long number) {

    public DocumentNumber {
        Objects.requireNonNull(prefix);
        if (number <= 0) {
            throw new IllegalArgumentException("document number must be positive");
        }
    }

    public String formatted() {
        return prefix + number;
    }
}
