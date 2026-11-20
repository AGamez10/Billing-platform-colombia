package co.billingplatform.platform;

import co.billingplatform.identity.domain.Base32;
import co.billingplatform.identity.domain.Totp;
import co.billingplatform.platform.mail.InMemoryMailbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Verificación en dos pasos por HTTP real (ADR-0034). */
class TwoFactorApiTest extends ApiTest {

    private static final String PASSWORD = "caballo bateria grapa";

    @Autowired
    InMemoryMailbox mailbox;

    @Autowired
    JdbcClient jdbc;

    private HttpResponse<String> login(String email) throws Exception {
        return send("POST", "/api/v1/auth/login", null, "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
    }

    private HttpResponse<String> secondStep(String challenge, String code) throws Exception {
        return send("POST", "/api/v1/auth/login/two-factor", null,
                "{\"challenge\":\"%s\",\"code\":\"%s\"}".formatted(challenge, code));
    }

    private String awaitMail(String email, String subjectPart) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var last = mailbox.lastTo(email);
            if (last.isPresent() && last.get().subject().contains(subjectPart)) {
                return last.get().subject();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no llegó el correo '" + subjectPart + "' a " + email);
    }

    private static String code(byte[] secret, int stepsFromNow) {
        return Totp.code(secret, Totp.stepAt(Instant.now()) + stepsFromNow);
    }

    private static String firstRecoveryCode(String json) {
        Matcher m = Pattern.compile("\"recoveryCodes\":\\[\"([A-Z0-9-]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    @Test
    @DisplayName("activar, entrar con código o de recuperación, y desactivar con contraseña y código")
    void fullFlow() throws Exception {
        String email = "dos-pasos-" + UUID.randomUUID() + "@example.com";
        String token = registerOrganization(email);

        assertEquals("{\"enabled\":false,\"recoveryCodesLeft\":0}", send("GET", "/api/v1/account/two-factor", token, null).body());

        HttpResponse<String> setup = send("POST", "/api/v1/account/two-factor/setup", token, null);
        assertEquals(200, setup.statusCode(), setup.body());
        byte[] secret = Base32.decode(field(setup.body(), "secret"));
        assertTrue(setup.body().contains("otpauth://totp/"), setup.body());

        // El secreto queda cifrado en la base, nunca en claro.
        byte[] stored = jdbc.sql("SELECT secret_sealed FROM user_two_factor ut JOIN users u ON u.id = ut.user_id WHERE u.email = :e")
                .param("e", email).query(byte[].class).single();
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains(new String(secret, java.nio.charset.StandardCharsets.ISO_8859_1)));

        HttpResponse<String> bad = send("POST", "/api/v1/account/two-factor/enable", token, "{\"code\":\"12345\"}");
        assertEquals(422, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("INVALID_TWO_FACTOR_CODE"), bad.body());

        HttpResponse<String> enabled = send("POST", "/api/v1/account/two-factor/enable", token,
                "{\"code\":\"" + code(secret, 0) + "\"}");
        assertEquals(200, enabled.statusCode(), enabled.body());
        String recovery = firstRecoveryCode(enabled.body());
        awaitMail(email, "activada");

        // La contraseña sola ya no abre sesión.
        HttpResponse<String> first = login(email);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"status\":\"TWO_FACTOR_REQUIRED\""), first.body());
        assertFalse(first.body().contains("accessToken\":\""), "sin token antes del código");
        assertTrue(first.headers().allValues("Set-Cookie").isEmpty(), "sin cookie de sesión antes del código");
        String challenge = field(first.body(), "challenge");

        HttpResponse<String> replay = secondStep(challenge, code(secret, 0));
        assertEquals(422, replay.statusCode(), "el código usado al activar no sirve otra vez: " + replay.body());

        HttpResponse<String> ok = secondStep(challenge, code(secret, 1));
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"status\":\"SIGNED_IN\""), ok.body());
        assertFalse(ok.headers().allValues("Set-Cookie").isEmpty(), "la cookie de sesión llega con el código");
        String session = field(ok.body(), "accessToken");
        assertEquals(401, secondStep(challenge, code(secret, 1)).statusCode(), "el reto sirve una sola vez");

        // Código de recuperación (sin teléfono).
        String challenge2 = field(login(email).body(), "challenge");
        assertEquals(200, secondStep(challenge2, recovery.toLowerCase()).statusCode());
        assertTrue(send("GET", "/api/v1/account/two-factor", session, null).body().contains("\"recoveryCodesLeft\":9"));

        // Desactivar exige contraseña y un código.
        HttpResponse<String> wrongPassword = send("POST", "/api/v1/account/two-factor/disable", session,
                "{\"password\":\"otra clave cualquiera\",\"code\":\"" + recovery + "\"}");
        assertEquals(422, wrongPassword.statusCode(), wrongPassword.body());
        HttpResponse<String> regenerated = send("POST", "/api/v1/account/two-factor/recovery-codes", session,
                "{\"code\":\"" + recovery + "\"}");
        assertEquals(422, regenerated.statusCode(), "un código de recuperación usado no sirve: " + regenerated.body());
        String other = firstRecoveryCode(enabled.body().replaceFirst("\"" + recovery + "\",", ""));
        HttpResponse<String> off = send("POST", "/api/v1/account/two-factor/disable", session,
                "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + other + "\"}");
        assertEquals(204, off.statusCode(), off.body());
        awaitMail(email, "desactivada");
        HttpResponse<String> plain = login(email);
        assertTrue(plain.body().contains("\"status\":\"SIGNED_IN\""), plain.body());
    }

    @Test
    @DisplayName("una clave de API no puede tocar la verificación en dos pasos")
    void apiKeysCannotManageTwoFactor() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> key = send("POST", "/api/v1/api-keys", token,
                "{\"name\":\"tienda\",\"scopes\":[\"INVOICE_READ\"]}");
        assertEquals(201, key.statusCode(), key.body());
        HttpResponse<String> res = send("POST", "/api/v1/account/two-factor/setup", field(key.body(), "key"), null);
        assertEquals(403, res.statusCode(), res.body());
    }

    @Test
    @DisplayName("sin sesión no se puede configurar, y un reto inventado no abre sesión")
    void requiresSession() throws Exception {
        assertEquals(401, send("POST", "/api/v1/account/two-factor/setup", null, null).statusCode());
        HttpResponse<String> fake = secondStep("reto-inventado", "123456");
        assertEquals(401, fake.statusCode(), fake.body());
        assertTrue(fake.body().contains("TWO_FACTOR_CHALLENGE_EXPIRED"), fake.body());
    }
}
