package co.billingplatform.customers.domain;

import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomerAndProductTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Test
    @DisplayName("cliente: valida NIT, correo, teléfono y código DANE; cada cambio sube la versión")
    void customer() {
        Customer c = Customer.create(tenant, PartyIdentification.nit("800197268", 4), "  DIAN  ", "A@B.CO", "601 555 0000", null, "11001", null);
        assertEquals("DIAN", c.legalName());
        assertEquals("a@b.co", c.email());
        assertEquals(0, c.version());
        assertEquals(1, c.withDetails(c.identification(), "Otro", null, null, null, null, FiscalProfile.naturalNotResponsible()).version());
        assertFalse(c.deactivated().active());

        assertThrows(DomainException.class, () -> Customer.create(tenant, PartyIdentification.of(IdentificationType.CEDULA_CIUDADANIA, "1"),
                "X", null, "abc", null, null, null));
        assertThrows(DomainException.class, () -> Customer.create(tenant, PartyIdentification.of(IdentificationType.CEDULA_CIUDADANIA, "1"),
                "X", null, null, null, "BOG", null));
    }

    @Test
    @DisplayName("producto: precio no negativo con máximo 6 decimales, SKU válido, tarifas sin repetir")
    void product() {
        Product p = Product.create(tenant, " SKU-1 ", " Consulta ", Product.Kind.SERVICE, "94", new BigDecimal("50000"), List.of("IVA_19"));
        assertEquals("SKU-1", p.sku());
        assertEquals("Consulta", p.name());
        assertEquals("40000.5", Product.create(tenant, null, "X", Product.Kind.PRODUCT, "94", new BigDecimal("40000.50"), null)
                .unitPrice().toPlainString(), "precio en forma canónica");
        assertEquals("10000", Product.create(tenant, null, "X", Product.Kind.PRODUCT, "94", new BigDecimal("10000.000000"), null)
                .unitPrice().toPlainString());
        assertThrows(DomainException.class, () -> Product.create(tenant, null, "X", Product.Kind.PRODUCT, "94", new BigDecimal("-1"), null));
        assertThrows(DomainException.class, () -> Product.create(tenant, null, "X", Product.Kind.PRODUCT, "94", new BigDecimal("1.1234567"), null));
        assertThrows(DomainException.class, () -> Product.create(tenant, "con espacio", "X", Product.Kind.PRODUCT, "94", BigDecimal.ONE, null));
        assertThrows(DomainException.class, () -> Product.create(tenant, null, "X", Product.Kind.PRODUCT, "94", BigDecimal.ONE, List.of("IVA_19", "IVA_19")));
        assertThrows(DomainException.class, () -> Product.create(tenant, null, "X", null, "94", BigDecimal.ONE, null));
    }

    @Test
    @DisplayName("paginación: límite por defecto 20, máximo 100, y detecta si hay más")
    void paging() {
        assertEquals(20, Page.clampLimit(null));
        assertEquals(100, Page.clampLimit(5000));
        assertEquals(0, Page.clampOffset(-3));
        Page<Integer> page = Page.of(List.of(1, 2, 3), 2, 0);
        assertEquals(List.of(1, 2), page.items());
        assertTrue(page.hasMore());
        assertFalse(Page.of(List.of(1, 2), 2, 0).hasMore());
    }
}
