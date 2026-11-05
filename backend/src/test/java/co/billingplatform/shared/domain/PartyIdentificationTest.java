package co.billingplatform.shared.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PartyIdentificationTest {

    @Test
    @DisplayName("calcula el DV del NIT (caso conocido: NIT de la DIAN 800197268-4)")
    void computesCheckDigit() {
        assertEquals(4, NitCheckDigit.compute("800197268"));
    }

    @Test
    @DisplayName("acepta NIT con DV correcto y rechaza DV incorrecto")
    void validatesNit() {
        assertDoesNotThrow(() -> PartyIdentification.nit("800197268", 4));
        assertEquals("INVALID_NIT", assertThrows(DomainException.class,
                () -> PartyIdentification.nit("800197268", 5)).code());
        assertEquals("INVALID_NIT", assertThrows(DomainException.class,
                () -> PartyIdentification.nit("80019726-8", 4)).code());
    }

    @Test
    @DisplayName("solo el NIT lleva dígito de verificación")
    void onlyNitHasCheckDigit() {
        assertDoesNotThrow(() -> PartyIdentification.of(IdentificationType.CEDULA_CIUDADANIA, "1020304050"));
        assertThrows(DomainException.class,
                () -> new PartyIdentification(IdentificationType.CEDULA_CIUDADANIA, "1020304050", 3));
    }

    @Test
    @DisplayName("normaliza el correo y valida datos obligatorios de la parte")
    void validatesParty() {
        Party party = new Party(PartyIdentification.of(IdentificationType.CEDULA_CIUDADANIA, "123456"),
                "  Ana Pérez ", " Ana@Example.COM ");
        assertEquals("Ana Pérez", party.legalName());
        assertEquals("ana@example.com", party.email());
        assertThrows(DomainException.class, () -> new Party(party.identification(), " ", null));
        assertThrows(DomainException.class, () -> new Party(party.identification(), "Ana", "no-es-correo"));
    }
}
