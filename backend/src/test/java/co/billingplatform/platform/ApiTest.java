package co.billingplatform.platform;

import co.billingplatform.shared.domain.NitCheckDigit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Base para pruebas HTTP reales: servidor en puerto aleatorio + PostgreSQL real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ApiTest extends PostgresIntegrationTest {

    @Autowired
    Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    protected HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        return send(method, path, token, json, java.util.Map.of());
    }

    protected HttpResponse<String> send(String method, String path, String token, String json,
                                        java.util.Map<String, String> headers) throws Exception {
        URI uri = URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"?([^\",}]+)\"?").matcher(json);
        assertTrue(m.find(), "falta " + name + " en " + json);
        return m.group(1);
    }

    protected static String randomNitJson() {
        String nit = Long.toString(ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L));
        return "\"identificationType\":\"NIT\",\"identificationNumber\":\"%s\",\"checkDigit\":%d"
                .formatted(nit, NitCheckDigit.compute(nit));
    }

    /** Registra una organización nueva y devuelve el access token de su dueño. */
    protected String registerOrganization() throws Exception {
        String body = "{\"organizationName\":\"Org Prueba\"," + randomNitJson()
                + ",\"fullName\":\"Dueño\",\"email\":\"o-" + UUID.randomUUID() + "@example.com\",\"password\":\"caballo bateria grapa\""
                + ",\"acceptedTermsVersion\":\"borrador-2026-11\",\"acceptedPrivacyVersion\":\"borrador-2026-11\"}";
        HttpResponse<String> res = send("POST", "/api/v1/auth/register", null, body);
        assertTrue(res.statusCode() == 201, res.body());
        return field(res.body(), "accessToken");
    }
}
