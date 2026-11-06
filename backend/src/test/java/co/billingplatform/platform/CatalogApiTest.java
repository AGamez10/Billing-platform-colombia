package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Clientes y productos por HTTP real: flujo, validaciones, bloqueo optimista y aislamiento. */
class CatalogApiTest extends ApiTest {

    private static String customerJson(String extra) {
        return "{" + randomNitJson() + ",\"legalName\":\"Cliente Uno S.A.S.\",\"email\":\"Compras@Cliente.co\"" + extra + "}";
    }

    @Test
    @DisplayName("clientes: crear, buscar, consultar, actualizar con versión y desactivar")
    void customerLifecycle() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> created = send("POST", "/api/v1/customers", token, customerJson(",\"cityCode\":\"11001\""));
        assertEquals(201, created.statusCode(), created.body());
        String id = field(created.body(), "id");
        assertEquals("compras@cliente.co", field(created.body(), "email"), "el correo se normaliza");

        HttpResponse<String> found = send("GET", "/api/v1/customers?q=uno", token, null);
        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains(id), found.body());
        assertEquals(200, send("GET", "/api/v1/customers?q=" + java.net.URLEncoder.encode("%", "UTF-8"), token, null).statusCode(),
                "los comodines del texto de búsqueda se tratan como texto");

        String update = "{\"version\":0,\"customer\":{\"identificationType\":\"CEDULA_CIUDADANIA\",\"identificationNumber\":\"1020304050\","
                + "\"legalName\":\"Cliente Renombrado\"}}";
        HttpResponse<String> updated = send("PUT", "/api/v1/customers/" + id, token, update);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("1", field(updated.body(), "version"));

        HttpResponse<String> stale = send("PUT", "/api/v1/customers/" + id, token, update);
        assertEquals(409, stale.statusCode(), "una versión vieja no pisa cambios de otro usuario");
        assertEquals("VERSION_CONFLICT", field(stale.body(), "code"));

        HttpResponse<String> deactivated = send("DELETE", "/api/v1/customers/" + id, token, null);
        assertEquals(200, deactivated.statusCode());
        assertEquals("false", field(deactivated.body(), "active"));
        assertFalse(send("GET", "/api/v1/customers", token, null).body().contains(id), "por defecto no lista inactivos");
        assertTrue(send("GET", "/api/v1/customers?includeInactive=true", token, null).body().contains(id));
    }

    @Test
    @DisplayName("clientes: validaciones y duplicados con códigos estables")
    void customerValidation() throws Exception {
        String token = registerOrganization();
        String body = customerJson("");
        assertEquals(201, send("POST", "/api/v1/customers", token, body).statusCode());
        HttpResponse<String> dup = send("POST", "/api/v1/customers", token, body);
        assertEquals(409, dup.statusCode());
        assertEquals("CUSTOMER_ALREADY_EXISTS", field(dup.body(), "code"));

        HttpResponse<String> badDv = send("POST", "/api/v1/customers", token,
                "{\"identificationType\":\"NIT\",\"identificationNumber\":\"800197268\",\"checkDigit\":5,\"legalName\":\"X\"}");
        assertEquals(422, badDv.statusCode());
        assertEquals("INVALID_NIT", field(badDv.body(), "code"));

        HttpResponse<String> badCity = send("POST", "/api/v1/customers", token, customerJson(",\"cityCode\":\"BOG\""));
        assertEquals(422, badCity.statusCode());
        HttpResponse<String> invalid = send("POST", "/api/v1/customers", token, "{\"legalName\":\"Sin identificación\"}");
        assertEquals(400, invalid.statusCode());
        assertEquals("VALIDATION_FAILED", field(invalid.body(), "code"));
        assertTrue(invalid.body().contains("\"field\":\"identificationType\""), "la interfaz puede marcar el campo: " + invalid.body());
    }

    @Test
    @DisplayName("aislamiento: otra organización no ve, no modifica ni desactiva mis clientes y productos")
    void tenantIsolation() throws Exception {
        String a = registerOrganization();
        String b = registerOrganization();
        String customerId = field(send("POST", "/api/v1/customers", a, customerJson("")).body(), "id");
        String productId = field(send("POST", "/api/v1/products", a,
                "{\"name\":\"Consulta veterinaria\",\"kind\":\"SERVICE\",\"unitCode\":\"94\",\"unitPrice\":\"50000\",\"taxRateCodes\":[\"IVA_19\"]}").body(), "id");

        assertEquals(404, send("GET", "/api/v1/customers/" + customerId, b, null).statusCode());
        assertEquals(404, send("DELETE", "/api/v1/customers/" + customerId, b, null).statusCode());
        assertEquals(404, send("PUT", "/api/v1/customers/" + customerId, b,
                "{\"version\":0,\"customer\":{\"identificationType\":\"CEDULA_CIUDADANIA\",\"identificationNumber\":\"1\",\"legalName\":\"x\"}}").statusCode());
        assertFalse(send("GET", "/api/v1/customers", b, null).body().contains(customerId));
        assertEquals(404, send("GET", "/api/v1/products/" + productId, b, null).statusCode());
        assertFalse(send("GET", "/api/v1/products", b, null).body().contains(productId));

        assertEquals(200, send("GET", "/api/v1/customers/" + customerId, a, null).statusCode(), "el dueño sí lo ve");
    }

    @Test
    @DisplayName("productos: precio como texto decimal, tarifas vigentes y una sola por tributo")
    void productValidation() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> ok = send("POST", "/api/v1/products", token,
                "{\"sku\":\"LIB-001\",\"name\":\"Libro\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"40000.50\",\"taxRateCodes\":[\"IVA_EXCLUIDO\"]}");
        assertEquals(201, ok.statusCode(), ok.body());
        assertEquals("40000.5", field(ok.body(), "unitPrice"));

        HttpResponse<String> dupSku = send("POST", "/api/v1/products", token,
                "{\"sku\":\"LIB-001\",\"name\":\"Otro\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"1\"}");
        assertEquals("PRODUCT_SKU_ALREADY_EXISTS", field(dupSku.body(), "code"));

        HttpResponse<String> unknownTax = send("POST", "/api/v1/products", token,
                "{\"name\":\"X\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"1\",\"taxRateCodes\":[\"IVA_99\"]}");
        assertEquals(422, unknownTax.statusCode());
        assertEquals("TAX_RATE_NOT_FOUND", field(unknownTax.body(), "code"));

        HttpResponse<String> twoIva = send("POST", "/api/v1/products", token,
                "{\"name\":\"X\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"1\",\"taxRateCodes\":[\"IVA_19\",\"IVA_5\"]}");
        assertEquals("DUPLICATE_TAX_TYPE", field(twoIva.body(), "code"));

        HttpResponse<String> negative = send("POST", "/api/v1/products", token,
                "{\"name\":\"X\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"-1\"}");
        assertEquals(422, negative.statusCode());
        HttpResponse<String> notNumber = send("POST", "/api/v1/products", token,
                "{\"name\":\"X\",\"kind\":\"PRODUCT\",\"unitCode\":\"94\",\"unitPrice\":\"diez\"}");
        assertEquals(422, notNumber.statusCode());
    }

    @Test
    @DisplayName("sin token no hay acceso al catálogo")
    void requiresAuthentication() throws Exception {
        assertEquals(401, send("GET", "/api/v1/customers", null, null).statusCode());
        assertEquals(401, send("POST", "/api/v1/products", null, "{}").statusCode());
    }
}
