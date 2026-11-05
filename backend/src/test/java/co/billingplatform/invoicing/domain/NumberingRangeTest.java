package co.billingplatform.invoicing.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NumberingRangeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 12, 31);

    @Test
    @DisplayName("asigna consecutivos dentro del rango autorizado")
    void allocatesSequentially() {
        NumberingRange range = NumberingRange.create(TENANT, "18760000001", "SETP", 990000000, 990000002, FROM, TO);
        LocalDate day = LocalDate.of(2026, 11, 5);

        assertEquals(new DocumentNumber("SETP", 990000000), range.allocate(day));
        assertEquals("SETP990000001", range.allocate(day).formatted());
        assertEquals(1, range.remaining());
        range.allocate(day);
        assertEquals("NUMBERING_RANGE_EXHAUSTED", assertThrows(DomainException.class, () -> range.allocate(day)).code());
    }

    @Test
    @DisplayName("no asigna números fuera de la vigencia de la resolución")
    void rejectsOutsideValidity() {
        NumberingRange range = NumberingRange.create(TENANT, "R-1", "FE", 1, 100, FROM, TO);
        assertEquals("NUMBERING_RESOLUTION_NOT_VALID",
                assertThrows(DomainException.class, () -> range.allocate(LocalDate.of(2028, 1, 1))).code());
        assertEquals(1, range.nextNumber(), "un intento fallido no consume número");
    }

    @Test
    @DisplayName("valida la definición del rango")
    void validatesDefinition() {
        assertThrows(DomainException.class, () -> NumberingRange.create(TENANT, "R", "FE", 10, 5, FROM, TO));
        assertThrows(DomainException.class, () -> NumberingRange.create(TENANT, "R", "fe", 1, 5, FROM, TO));
        assertThrows(DomainException.class, () -> NumberingRange.create(TENANT, "R", "FE", 1, 5, TO, FROM));
        assertThrows(DomainException.class, () -> NumberingRange.create(TENANT, " ", "FE", 1, 5, FROM, TO));
    }
}
