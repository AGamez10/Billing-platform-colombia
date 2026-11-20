package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DashboardApiTest extends ApiTest {

    @Test
    @DisplayName("inicio: pendientes de configuración y ventas del mes (aceptadas menos notas crédito) en el ambiente activo")
    void dashboard() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> empty = send("GET", "/api/v1/dashboard", token, null);
        assertEquals(200, empty.statusCode(), empty.body());
        assertTrue(empty.body().contains("\"code\":\"PROVIDER_MISSING\""), empty.body());

        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        assertTrue(send("GET", "/api/v1/dashboard", token, null).body().contains("\"code\":\"NUMBERING_MISSING\""));

        LocalDate today = LocalDate.now();
        assertEquals(201, send("POST", "/api/v1/numbering-ranges", token,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000021\",\"prefix\":\"DB\","
                        + "\"rangeFrom\":1,\"rangeTo\":12,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(today.minusDays(2), today.plusDays(10))).statusCode());
        String customerId = field(send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"Cliente Inicio\"}").body(), "id");
        String id = field(send("POST", "/api/v1/invoices", token, """
                {"customerId":"%s","lines":[{"description":"Servicio","unitCode":"94","quantity":"1","unitPrice":"100000",
                 "taxRateCodes":["IVA_19"]}]}""".formatted(customerId)).body(), "id");
        assertEquals(200, send("POST", "/api/v1/invoices/" + id + "/finalize", token, null).statusCode());
        long deadline = System.currentTimeMillis() + 30_000;
        while (!field(send("GET", "/api/v1/invoices/" + id, token, null).body(), "status").equals("ACCEPTED")) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.sleep(200);
        }

        String body = send("GET", "/api/v1/dashboard", token, null).body();
        assertEquals("SIMULATION", field(body, "environment"));
        assertTrue(body.contains("\"total\":119000.00"), body);
        assertTrue(body.contains("\"documents\":1"), body);
        assertTrue(body.contains("NUMBERING_EXPIRING") && body.contains("NUMBERING_LOW"), "vence en 10 días y quedan 11 de 12: " + body);
        assertTrue(body.contains("ISSUER_PROFILE_INCOMPLETE"));
        assertFalse(body.contains("PROVIDER_MISSING"));

        assertFalse(send("GET", "/api/v1/dashboard", registerOrganization(), null).body().contains("119000"),
                "otra organización no ve estas ventas");
    }
}
