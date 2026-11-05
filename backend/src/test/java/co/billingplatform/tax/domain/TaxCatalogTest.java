package co.billingplatform.tax.domain;

import co.billingplatform.shared.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaxCatalogTest {

    private static final TaxRate OLD = new TaxRate("TEST_GENERAL", TaxType.IVA, TaxTreatment.TAXED,
            new BigDecimal("10"), LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), "test fixture");
    private static final TaxRate NEW = new TaxRate("TEST_GENERAL", TaxType.IVA, TaxTreatment.TAXED,
            new BigDecimal("12"), LocalDate.of(2027, 1, 1), null, "test fixture");

    @Test
    @DisplayName("resuelve la tarifa vigente según la fecha de emisión")
    void resolvesByDate() {
        TaxCatalog catalog = TaxCatalog.of(List.of(OLD, NEW));
        assertEquals(new BigDecimal("10"), catalog.resolve("TEST_GENERAL", LocalDate.of(2026, 12, 31)).percentage());
        assertEquals(new BigDecimal("12"), catalog.resolve("TEST_GENERAL", LocalDate.of(2027, 1, 1)).percentage());
    }

    @Test
    @DisplayName("falla si no hay tarifa o si las vigencias se superponen")
    void failsWhenMissingOrAmbiguous() {
        assertEquals("TAX_RATE_NOT_FOUND", assertThrows(DomainException.class,
                () -> TaxCatalog.of(List.of(OLD)).resolve("TEST_GENERAL", LocalDate.of(2027, 1, 1))).code());

        TaxRate overlapping = new TaxRate("TEST_GENERAL", TaxType.IVA, TaxTreatment.TAXED,
                new BigDecimal("11"), LocalDate.of(2026, 6, 1), null, "test fixture");
        assertEquals("TAX_RATE_NOT_FOUND", assertThrows(DomainException.class,
                () -> TaxCatalog.of(List.of(OLD, overlapping)).resolve("TEST_GENERAL", LocalDate.of(2026, 7, 1))).code());
    }
}
