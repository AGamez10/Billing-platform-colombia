package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Claves de API, Idempotency-Key en borradores y avisos salientes (sin salir a internet). */
class IntegrationApiTest extends ApiTest {

    private String issueKey(String owner, String scopes) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/api-keys", owner, "{\"name\":\"AdoptaFácil\",\"scopes\":[" + scopes + "]}");
        assertEquals(201, r.statusCode(), r.body());
        String key = field(r.body(), "key");
        assertTrue(key.matches("bpk_[0-9a-f]{12}_[A-Za-z0-9_-]{43}"), key);
        return key;
    }

    @Test
    @DisplayName("clave de API: opera con sus permisos, no administra, no ve otra organización y deja de servir al revocarla")
    void apiKeys() throws Exception {
        String owner = registerOrganization();
        String key = issueKey(owner, "\"CUSTOMER_WRITE\",\"CUSTOMER_READ\",\"INVOICE_READ\"");
        assertEquals(422, send("POST", "/api/v1/api-keys", owner, "{\"name\":\"x\",\"scopes\":[\"USERS_MANAGE\"]}").statusCode(),
                "permisos administrativos no");

        HttpResponse<String> created = send("POST", "/api/v1/customers", key, "{" + randomNitJson() + ",\"legalName\":\"Desde la API\"}");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(200, send("GET", "/api/v1/invoices", key, null).statusCode());
        assertEquals(403, send("POST", "/api/v1/invoices", key, "{\"customerId\":\"" + field(created.body(), "id")
                + "\",\"lines\":[{\"description\":\"x\",\"unitCode\":\"94\",\"quantity\":\"1\",\"unitPrice\":\"1\",\"taxRateCodes\":[\"IVA_19\"]}]}").statusCode(),
                "sin INVOICE_WRITE no crea facturas");
        assertEquals(403, send("GET", "/api/v1/api-keys", key, null).statusCode(), "una clave no administra claves");
        assertEquals(403, send("GET", "/api/v1/team", key, null).statusCode(), "ni el equipo");

        String other = registerOrganization();
        assertFalse(send("GET", "/api/v1/customers?q=Desde", other, null).body().contains("Desde la API"));
        assertTrue(send("GET", "/api/v1/customers?q=Desde", key, null).body().contains("Desde la API"));

        String list = send("GET", "/api/v1/api-keys", owner, null).body();
        assertTrue(list.contains("\"lastUsedAt\":\"") && !list.contains(key), "último uso registrado y sin secreto: " + list);
        String id = field(list, "id");
        assertEquals(204, send("DELETE", "/api/v1/api-keys/" + id, owner, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/customers", key, null).statusCode(), "revocada no sirve");
        assertEquals(401, send("GET", "/api/v1/customers", key.substring(0, key.length() - 1) + "A", null).statusCode());
    }

    @Test
    @DisplayName("Idempotency-Key: el reintento devuelve el mismo borrador; otro contenido con la misma clave se rechaza")
    void idempotentDrafts() throws Exception {
        String owner = registerOrganization();
        String key = issueKey(owner, "\"CUSTOMER_WRITE\",\"INVOICE_WRITE\",\"INVOICE_READ\"");
        String customer = field(send("POST", "/api/v1/customers", key, "{" + randomNitJson() + ",\"legalName\":\"Idem\"}").body(), "id");
        String body = "{\"customerId\":\"" + customer + "\",\"lines\":[{\"description\":\"Venta\",\"unitCode\":\"94\",\"quantity\":\"1\","
                + "\"unitPrice\":\"1000\",\"taxRateCodes\":[\"IVA_19\"]}]}";
        Map<String, String> h = Map.of("Idempotency-Key", "pedido-af-000123");
        HttpResponse<String> first = send("POST", "/api/v1/invoices", key, body, h);
        HttpResponse<String> again = send("POST", "/api/v1/invoices", key, body, h);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(field(first.body(), "id"), field(again.body(), "id"));
        assertEquals(422, send("POST", "/api/v1/invoices", key, body.replace("1000", "2000"), h).statusCode());
        assertEquals(400, send("POST", "/api/v1/invoices", key, body, Map.of("Idempotency-Key", "x")).statusCode());
    }

    @Test
    @DisplayName("webhooks: registro HTTPS público, prueba encolada y aviso al validarse un documento")
    void webhooks() throws Exception {
        String owner = registerOrganization();
        assertEquals(422, send("POST", "/api/v1/webhook-endpoints", owner,
                "{\"url\":\"https://127.0.0.1/hook\",\"events\":[\"document.accepted\"]}").statusCode(), "SSRF: IP rechazada");
        HttpResponse<String> created = send("POST", "/api/v1/webhook-endpoints", owner,
                "{\"url\":\"https://hooks.example.com/billing\",\"events\":[\"document.accepted\",\"document.rejected\"]}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(field(created.body(), "secret").startsWith("whsec_"));
        String endpoint = field(created.body(), "id");
        assertFalse(send("GET", "/api/v1/webhook-endpoints", owner, null).body().contains("whsec_"), "el secreto no se vuelve a mostrar");
        assertEquals(202, send("POST", "/api/v1/webhook-endpoints/" + endpoint + "/test", owner, null).statusCode());

        assertEquals(201, send("POST", "/api/v1/provider-accounts", owner, "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        LocalDate today = LocalDate.now();
        assertEquals(201, send("POST", "/api/v1/numbering-ranges", owner,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000051\",\"prefix\":\"WH\",\"rangeFrom\":1,\"rangeTo\":100,"
                        + "\"validFrom\":\"%s\",\"validTo\":\"%s\"}").formatted(today.minusDays(2), today.plusYears(1))).statusCode());
        String customer = field(send("POST", "/api/v1/customers", owner, "{" + randomNitJson() + ",\"legalName\":\"Hook\"}").body(), "id");
        String invoice = field(send("POST", "/api/v1/invoices", owner, "{\"customerId\":\"" + customer + "\",\"lines\":[{\"description\":\"A\","
                + "\"unitCode\":\"94\",\"quantity\":\"1\",\"unitPrice\":\"1000\",\"taxRateCodes\":[\"IVA_19\"]}]}").body(), "id");
        send("POST", "/api/v1/invoices/" + invoice + "/finalize", owner, null);
        long deadline = System.currentTimeMillis() + 30_000;
        String deliveries;
        do {
            assertTrue(System.currentTimeMillis() < deadline, "no se encoló el aviso");
            Thread.sleep(200);
            deliveries = send("GET", "/api/v1/webhook-endpoints/" + endpoint + "/deliveries", owner, null).body();
        } while (!deliveries.contains("document.accepted"));
        assertTrue(deliveries.contains("\"event\":\"ping\""), deliveries);
        assertTrue(deliveries.contains("\"status\":\"PENDING\""), deliveries);

        assertEquals(404, send("GET", "/api/v1/webhook-endpoints/" + endpoint + "/deliveries", registerOrganization(), null).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/webhook-endpoints/" + endpoint, owner, null).statusCode());
    }
}
