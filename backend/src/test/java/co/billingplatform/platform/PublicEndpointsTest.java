package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Peticiones HTTP reales contra la aplicación levantada en un puerto aleatorio, con PostgreSQL real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicEndpointsTest extends PostgresIntegrationTest {

    @Autowired
    Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("planes públicos: los aprobados, con montos como texto")
    void publicPlans() throws Exception {
        HttpResponse<String> res = get("/api/v1/public/plans");
        assertEquals(200, res.statusCode());
        String body = res.body();
        for (String code : new String[] {"TRIAL", "MICRO", "EMPRENDEDOR", "NEGOCIO", "PRO",
                "COMERCIO_500", "COMERCIO_1200", "COMERCIO_3000"}) {
            assertTrue(body.contains("\"code\":\"" + code + "\""), "falta el plan " + code + ": " + body);
        }
        assertTrue(body.contains("\"annualPrice\":\"69000.00\""), body);
        // Comercio: pago anual de 11 meses y excedente propio de $60
        assertTrue(body.contains("\"code\":\"COMERCIO_500\",\"name\":\"Comercio 500\",\"family\":\"COMMERCE\""), body);
        assertTrue(body.contains("\"annualPrice\":\"383900.00\",\"overageUnitPrice\":\"60.00\""), body);
        assertTrue(body.indexOf("\"PRO\"") < body.indexOf("\"COMERCIO_500\""), "los planes generales van antes que los de comercio");
    }

    @Test
    @DisplayName("tarifas vigentes con su fundamento legal")
    void taxRates() throws Exception {
        HttpResponse<String> res = get("/api/v1/public/tax-rates");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"code\":\"IVA_19\""), res.body());
        assertTrue(res.body().contains("Estatuto Tributario art. 468"), res.body());
    }

    @Test
    @DisplayName("página de estado, salud y documentación de la API son públicas")
    void publicPages() throws Exception {
        assertEquals(200, get("/").statusCode());
        assertTrue(get("/").body().contains("Billing Platform Colombia"));
        assertEquals(200, get("/actuator/health").statusCode());
        assertEquals(200, get("/v3/api-docs").statusCode());
    }

    @Test
    @DisplayName("todo lo demás está cerrado por defecto, sin revelar detalles")
    void everythingElseIsClosed() throws Exception {
        for (String path : new String[] {"/api/v1/invoices", "/actuator/env", "/actuator/beans", "/api/v1/public/../invoices"}) {
            int status = get(path).statusCode();
            assertTrue(status == 401 || status == 403 || status == 400 || status == 404,
                    path + " respondió " + status);
        }
    }
}
