package co.billingplatform.platform;

import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.NitCheckDigit;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Registro, login y sesión por HTTP real, contra PostgreSQL real con RLS. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "caballo bateria grapa";

    @Autowired Environment environment;
    @Autowired JdbcClient jdbc;
    @Autowired TenantTransactions tx;

    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ utilidades HTTP

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), "falta " + name + " en " + json);
        return m.group(1);
    }

    private static String refreshCookie(HttpResponse<String> res) {
        String header = res.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("bp_refresh=")).findFirst().orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }

    private record Org(String nit, int dv, String email) {}

    private static Org newOrg() {
        String nit = Long.toString(ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L));
        return new Org(nit, NitCheckDigit.compute(nit), "owner-" + UUID.randomUUID() + "@example.com");
    }

    private static String registerJson(Org o, String password) {
        return """
                {"organizationName":"Fundación Prueba","identificationType":"NIT","identificationNumber":"%s",
                 "checkDigit":%d,"fullName":"Ana Prueba","email":"%s","password":"%s"}
                """.formatted(o.nit(), o.dv(), o.email(), password);
    }

    private HttpResponse<String> register(Org o) throws Exception {
        return post("/api/v1/auth/register", registerJson(o, PASSWORD));
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    @DisplayName("registro: crea organización, dueño y prueba gratis, y abre sesión con cookie segura")
    void registerStartsSession() throws Exception {
        HttpResponse<String> res = register(newOrg());

        assertEquals(201, res.statusCode(), res.body());
        assertEquals("SIGNED_IN", field(res.body(), "status"));
        String cookie = res.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("bp_refresh=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Strict")
                && cookie.contains("Path=/api/v1/auth"), cookie);

        HttpResponse<String> me = get("/api/v1/auth/me", field(res.body(), "accessToken"));
        assertEquals(200, me.statusCode(), me.body());
        assertEquals("OWNER", field(me.body(), "role"));
        assertFalse(field(me.body(), "organizationName").isBlank(), "la interfaz muestra el nombre de la organización");

        TenantId tenant = new TenantId(UUID.fromString(field(me.body(), "organizationId")));
        long trials = tx.inTenant(tenant, () -> jdbc.sql(
                "SELECT count(*) FROM subscriptions WHERE plan_code = 'TRIAL' AND status = 'TRIAL'").query(Long.class).single());
        assertEquals(1, trials, "la organización nueva arranca con la prueba gratis");
    }

    @Test
    @DisplayName("sin token, con token inválido o alterado no hay acceso")
    void protectedEndpointsRequireValidToken() throws Exception {
        assertEquals(401, get("/api/v1/auth/me", null).statusCode());
        assertEquals(401, get("/api/v1/auth/me", "no-es-un-jwt").statusCode());
        String token = field(register(newOrg()).body(), "accessToken");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        assertEquals(401, get("/api/v1/auth/me", tampered).statusCode());
    }

    @Test
    @DisplayName("login: correcto entra; incorrecto o usuario inexistente responden igual")
    void login() throws Exception {
        Org o = newOrg();
        register(o);
        assertEquals(200, login(o.email(), PASSWORD).statusCode());

        HttpResponse<String> wrong = login(o.email(), "otra clave equivocada");
        HttpResponse<String> unknown = login("nadie-" + UUID.randomUUID() + "@example.com", PASSWORD);
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals(field(wrong.body(), "code"), field(unknown.body(), "code"));
        assertEquals(field(wrong.body(), "detail"), field(unknown.body(), "detail"));
    }

    @Test
    @DisplayName("bloqueo: tras 5 fallos ni la contraseña correcta entra (el contador sí se guarda)")
    void lockout() throws Exception {
        Org o = newOrg();
        register(o);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(o.email(), "otra clave equivocada").statusCode());
        }
        assertEquals(401, login(o.email(), PASSWORD).statusCode());
    }

    @Test
    @DisplayName("renovación: rota la cookie; reutilizar la anterior cierra todas las sesiones")
    void refreshRotationAndReuse() throws Exception {
        String first = refreshCookie(register(newOrg()));

        assertEquals(400, post("/api/v1/auth/refresh", null, "Cookie", first).statusCode(),
                "sin la cabecera propia no se renueva (defensa CSRF)");

        HttpResponse<String> rotated = post("/api/v1/auth/refresh", null, "Cookie", first, "X-Billing-Client", "web");
        assertEquals(200, rotated.statusCode(), rotated.body());
        String second = refreshCookie(rotated);
        assertNotEquals(first, second);
        assertEquals(200, get("/api/v1/auth/me", field(rotated.body(), "accessToken")).statusCode());

        HttpResponse<String> reuse = post("/api/v1/auth/refresh", null, "Cookie", first, "X-Billing-Client", "web");
        assertEquals(401, reuse.statusCode());
        assertEquals("REFRESH_TOKEN_REUSED", field(reuse.body(), "code"));
        assertEquals(401, post("/api/v1/auth/refresh", null, "Cookie", second, "X-Billing-Client", "web").statusCode(),
                "la sesión legítima también quedó revocada");
    }

    @Test
    @DisplayName("cerrar sesión invalida la cookie de renovación")
    void logout() throws Exception {
        String cookie = refreshCookie(register(newOrg()));
        assertEquals(204, post("/api/v1/auth/logout", null, "Cookie", cookie, "X-Billing-Client", "web").statusCode());
        assertEquals(401, post("/api/v1/auth/refresh", null, "Cookie", cookie, "X-Billing-Client", "web").statusCode());
    }

    @Test
    @DisplayName("validaciones de registro: duplicados, NIT con DV errado, contraseña débil, campos faltantes")
    void registrationValidation() throws Exception {
        Org o = newOrg();
        assertEquals(201, register(o).statusCode());

        Org sameNit = new Org(o.nit(), o.dv(), "otro-" + UUID.randomUUID() + "@example.com");
        assertEquals("ORGANIZATION_ALREADY_REGISTERED", field(register(sameNit).body(), "code"));

        Org other = newOrg();
        HttpResponse<String> dupEmail = register(new Org(other.nit(), other.dv(), o.email()));
        assertEquals(409, dupEmail.statusCode());
        assertEquals("EMAIL_ALREADY_REGISTERED", field(dupEmail.body(), "code"));

        Org badDv = newOrg();
        HttpResponse<String> invalidNit = post("/api/v1/auth/register", registerJson(new Org(badDv.nit(), (badDv.dv() + 1) % 10, badDv.email()), PASSWORD));
        assertEquals(422, invalidNit.statusCode());
        assertEquals("INVALID_NIT", field(invalidNit.body(), "code"));

        HttpResponse<String> weak = post("/api/v1/auth/register", registerJson(newOrg(), "corta"));
        assertEquals(422, weak.statusCode());
        assertEquals("WEAK_PASSWORD", field(weak.body(), "code"));

        assertEquals(400, post("/api/v1/auth/register", "{\"organizationName\":\"x\"}").statusCode());
        assertEquals(400, post("/api/v1/auth/register", "{no es json").statusCode());
    }

    @Test
    @DisplayName("aislamiento: cada sesión ve solo su propia organización")
    void sessionsAreIsolated() throws Exception {
        String a = field(register(newOrg()).body(), "accessToken");
        String b = field(register(newOrg()).body(), "accessToken");
        assertNotEquals(field(get("/api/v1/auth/me", a).body(), "organizationId"),
                field(get("/api/v1/auth/me", b).body(), "organizationId"));
    }
}
