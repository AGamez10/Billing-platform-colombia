package co.billingplatform.shared.domain;

import co.billingplatform.shared.domain.FiscalProfile.PersonType;
import co.billingplatform.shared.domain.FiscalProfile.TaxResponsibility;
import co.billingplatform.shared.domain.FiscalProfile.VatRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FiscalProfileTest {

    @Test
    @DisplayName("códigos DIAN: persona 1/2, régimen 48/49 y responsabilidades del RUT en orden estable")
    void codes() {
        FiscalProfile p = FiscalProfile.of(PersonType.LEGAL, VatRegime.RESPONSIBLE, List.of("o-23", " O-13 "));
        assertEquals("1", p.personType().code());
        assertEquals("48", p.vatRegime().code());
        assertEquals(List.of("O-13", "O-23"), p.responsibilityCodes());
        assertEquals("2", PersonType.NATURAL.code());
        assertEquals("49", VatRegime.NOT_RESPONSIBLE.code());
        assertEquals(List.of("R-99-PN"), FiscalProfile.naturalNotResponsible().responsibilityCodes());
    }

    @Test
    @DisplayName("perfil inválido: sin responsabilidades, códigos desconocidos o R-99-PN combinado")
    void invalid() {
        assertEquals("INVALID_FISCAL_PROFILE", assertThrows(DomainException.class,
                () -> FiscalProfile.of(PersonType.LEGAL, VatRegime.RESPONSIBLE, List.of())).code());
        assertThrows(DomainException.class, () -> FiscalProfile.of(PersonType.LEGAL, VatRegime.RESPONSIBLE, List.of("O-99")));
        assertThrows(DomainException.class, () -> new FiscalProfile(null, VatRegime.RESPONSIBLE, Set.of(TaxResponsibility.AUTORRETENEDOR)));
        assertThrows(DomainException.class, () -> new FiscalProfile(PersonType.NATURAL, VatRegime.NOT_RESPONSIBLE,
                Set.of(TaxResponsibility.NO_RESPONSABLE, TaxResponsibility.REGIMEN_SIMPLE)));
    }

    @Test
    @DisplayName("dirección: línea y municipio DANE de 5 dígitos, juntos; el departamento son los 2 primeros")
    void address() {
        FiscalAddress a = new FiscalAddress("  Calle   10  # 5-20 ", "76001");
        assertEquals("Calle 10 # 5-20", a.line());
        assertEquals("76", a.departmentCode());
        assertNull(FiscalAddress.ofNullable(" ", null));
        assertThrows(DomainException.class, () -> FiscalAddress.ofNullable("Calle 1", null));
        assertThrows(DomainException.class, () -> new FiscalAddress("Calle 1", "1100"));
        assertThrows(DomainException.class, () -> new FiscalAddress("x", "11001"));
    }

    @Test
    @DisplayName("el consumidor final lleva perfil de persona natural no responsable; la parte de 3 datos sigue valiendo")
    void party() {
        assertEquals(FiscalProfile.naturalNotResponsible(), Party.finalConsumer().fiscal());
        Party legacy = new Party(PartyIdentification.nit("800197268", 4), "DIAN", null);
        assertTrue(legacy.fiscalProfile().isEmpty());
        assertTrue(legacy.fiscalAddress().isEmpty());
    }
}
