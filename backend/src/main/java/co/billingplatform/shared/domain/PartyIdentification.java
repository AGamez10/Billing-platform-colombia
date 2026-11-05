package co.billingplatform.shared.domain;

import java.util.Objects;

/**
 * Identificación de una parte. Para NIT exige el dígito de verificación y lo valida,
 * evitando que un documento salga con un NIT mal digitado y sea rechazado.
 */
public record PartyIdentification(IdentificationType type, String number, Integer checkDigit) {

    public PartyIdentification {
        Objects.requireNonNull(type, "identification type is required");
        if (number == null || number.isBlank()) {
            throw new DomainException("INVALID_IDENTIFICATION", "Identification number is required");
        }
        number = number.strip();
        if (number.length() > 20) {
            throw new DomainException("INVALID_IDENTIFICATION", "Identification number is too long");
        }
        if (type == IdentificationType.NIT) {
            if (!number.matches("\\d{5,15}")) {
                throw new DomainException("INVALID_NIT", "NIT must contain only digits (without check digit)");
            }
            if (checkDigit == null) {
                throw new DomainException("INVALID_NIT", "NIT requires its check digit (DV)");
            }
            int expected = NitCheckDigit.compute(number);
            if (checkDigit != expected) {
                throw new DomainException("INVALID_NIT", "NIT check digit does not match");
            }
        } else if (checkDigit != null) {
            throw new DomainException("INVALID_IDENTIFICATION", "Only NIT carries a check digit");
        }
    }

    public static PartyIdentification nit(String number, int checkDigit) {
        return new PartyIdentification(IdentificationType.NIT, number, checkDigit);
    }

    public static PartyIdentification of(IdentificationType type, String number) {
        return new PartyIdentification(type, number, null);
    }
}
