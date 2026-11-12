package co.billingplatform.platform.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DivipolaCatalogTest {

    @Test
    @DisplayName("la DIVIPOLA versionada carga completa, con capitales verificables y nombres legibles")
    void loadsOfficialCatalog() {
        DivipolaCatalog catalog = DivipolaCatalog.fromClasspath();
        assertTrue(catalog.all().size() > 1100 && catalog.all().size() < 1200, "tamaño: " + catalog.all().size());
        assertEquals("Bogotá, D.C.", catalog.find("11001").orElseThrow().name());
        assertEquals("Santiago de Cali", catalog.find("76001").orElseThrow().name());
        assertEquals("Valle del Cauca", catalog.find("76001").orElseThrow().departmentName());
        assertEquals("05", catalog.find("05001").orElseThrow().departmentCode());
        assertTrue(catalog.find("00000").isEmpty());
        assertTrue(catalog.find(null).isEmpty());
        assertEquals("UNKNOWN_MUNICIPALITY", assertThrows(co.billingplatform.shared.domain.DomainException.class,
                () -> catalog.requireKnown("99999")).code());
        catalog.requireKnown(null);
    }

    @Test
    @DisplayName("nombres: conectores en minúscula, D.C. en mayúscula")
    void titles() {
        assertEquals("Archipiélago de San Andrés, Providencia y Santa Catalina",
                DivipolaCatalog.title("ARCHIPIÉLAGO DE SAN ANDRÉS, PROVIDENCIA Y SANTA CATALINA"));
        assertEquals("Piendamó - Tunía", DivipolaCatalog.title("PIENDAMÓ - TUNÍA"));
    }
}
