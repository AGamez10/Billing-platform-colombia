package co.billingplatform.platform;

import co.billingplatform.platform.mail.InMemoryMailbox;
import co.billingplatform.shared.application.Mailer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Recuperar contraseña e invitar al equipo por HTTP real, leyendo los correos del buzón de pruebas. */
class AccountApiTest extends ApiTest {

    private static final String PASSWORD = "caballo bateria grapa";

    @Autowired
    InMemoryMailbox mailbox;

    private String register(String email, String organization) throws Exception {
        String body = "{\"organizationName\":\"" + organization + "\"," + randomNitJson()
                + ",\"fullName\":\"Ana Dueña\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\""
                + ",\"acceptedTermsVersion\":\"borrador-2026-11\",\"acceptedPrivacyVersion\":\"borrador-2026-11\"}";
        HttpResponse<String> res = send("POST", "/api/v1/auth/register", null, body);
        assertEquals(201, res.statusCode(), res.body());
        return field(res.body(), "accessToken");
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return send("POST", "/api/v1/auth/login", null, "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private String awaitToken(String email, String path, long previousCount) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (mailbox.countTo(email) <= previousCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Mailer.Email mail = mailbox.lastTo(email).orElseThrow(() -> new AssertionError("no llegó correo a " + email));
        Matcher m = Pattern.compile("https://app\\.prueba\\.co" + path + "#t=([A-Za-z0-9_-]+)").matcher(mail.text());
        assertTrue(m.find(), mail.text());
        return m.group(1);
    }

    private static String email(String who) {
        return who + "-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    @DisplayName("recuperar contraseña: el enlace del correo cambia la clave una sola vez; no revela cuentas")
    void passwordReset() throws Exception {
        String ana = email("ana");
        register(ana, "Fundación Recupera");

        HttpResponse<String> unknown = send("POST", "/api/v1/auth/password-reset/request", null,
                "{\"email\":\"" + email("nadie") + "\"}");
        HttpResponse<String> known = send("POST", "/api/v1/auth/password-reset/request", null,
                "{\"email\":\"" + ana.toUpperCase() + "\"}");
        assertEquals(202, unknown.statusCode());
        assertEquals(202, known.statusCode());
        assertEquals(unknown.body(), known.body(), "misma respuesta exista o no la cuenta");

        String token = awaitToken(ana, "/restablecer", 0);
        HttpResponse<String> weak = send("POST", "/api/v1/auth/password-reset/confirm", null,
                "{\"token\":\"" + token + "\",\"password\":\"corta\"}");
        assertEquals("WEAK_PASSWORD", field(weak.body(), "code"), "una clave débil no gasta el enlace");
        HttpResponse<String> confirmed = send("POST", "/api/v1/auth/password-reset/confirm", null,
                "{\"token\":\"" + token + "\",\"password\":\"una clave nueva muy segura\"}");
        assertEquals(204, confirmed.statusCode(), confirmed.body());
        assertEquals(410, send("POST", "/api/v1/auth/password-reset/confirm", null,
                "{\"token\":\"" + token + "\",\"password\":\"otra clave nueva segura\"}").statusCode(), "un solo uso");

        assertEquals(401, login(ana, PASSWORD).statusCode(), "la clave anterior ya no sirve");
        assertEquals(200, login(ana, "una clave nueva muy segura").statusCode());
        assertTrue(mailbox.lastTo(ana).orElseThrow().subject().contains("cambió"), "se avisa del cambio");
    }

    @Test
    @DisplayName("equipo: invitar, aceptar con cuenta nueva, permisos por rol, cambiar rol y retirar acceso")
    void inviteNewMember() throws Exception {
        String owner = register(email("duena"), "Refugio Equipo");
        HttpResponse<String> initial = send("GET", "/api/v1/team", owner, null);
        assertEquals(200, initial.statusCode(), initial.body());
        assertEquals("true", field(initial.body(), "you"));

        String caja = email("caja");
        HttpResponse<String> invited = send("POST", "/api/v1/team/invitations", owner,
                "{\"email\":\"" + caja + "\",\"role\":\"BILLER\"}");
        assertEquals(201, invited.statusCode(), invited.body());
        String token = awaitToken(caja, "/invitacion", 0);
        assertFalse(send("GET", "/api/v1/team", owner, null).body().contains(token), "el enlace nunca vuelve por la API");

        HttpResponse<String> preview = send("POST", "/api/v1/auth/invitations/preview", null, "{\"token\":\"" + token + "\"}");
        assertEquals(200, preview.statusCode(), preview.body());
        assertEquals("Refugio Equipo", field(preview.body(), "organizationName"));
        assertEquals("BILLER", field(preview.body(), "role"));
        assertEquals("false", field(preview.body(), "existingAccount"));

        HttpResponse<String> accepted = send("POST", "/api/v1/auth/invitations/accept", null,
                ("{\"token\":\"%s\",\"fullName\":\"Carlos Caja\",\"password\":\"una clave para la caja\","
                        + "\"acceptedTermsVersion\":\"borrador-2026-11\",\"acceptedPrivacyVersion\":\"borrador-2026-11\"}").formatted(token));
        assertEquals(200, accepted.statusCode(), accepted.body());
        String cajaToken = field(accepted.body(), "accessToken");
        assertEquals("BILLER", field(send("GET", "/api/v1/auth/me", cajaToken, null).body(), "role"));
        assertEquals(403, send("GET", "/api/v1/team", cajaToken, null).statusCode(), "un facturador no administra el equipo");
        assertEquals(403, send("POST", "/api/v1/team/invitations", cajaToken,
                "{\"email\":\"" + email("x") + "\",\"role\":\"VIEWER\"}").statusCode());
        assertEquals(410, send("POST", "/api/v1/auth/invitations/preview", null, "{\"token\":\"" + token + "\"}").statusCode(),
                "una invitación aceptada no se reutiliza");

        HttpResponse<String> team = send("GET", "/api/v1/team", owner, null);
        Matcher member = Pattern.compile("\"userId\":\"([^\"]+)\",\"fullName\":\"[^\"]*\",\"email\":\"" + Pattern.quote(caja))
                .matcher(team.body());
        assertTrue(member.find(), team.body());
        String cajaId = member.group(1);
        assertEquals(204, send("PATCH", "/api/v1/team/members/" + cajaId, owner, "{\"role\":\"VIEWER\"}").statusCode());
        assertEquals(403, send("PATCH", "/api/v1/team/members/" + cajaId, owner, "{\"role\":\"OWNER\"}").statusCode());
        assertEquals(204, send("DELETE", "/api/v1/team/members/" + cajaId, owner, null).statusCode());
        assertEquals(401, login(caja, "una clave para la caja").statusCode(), "sin membresías activas no entra");

        String otherOwner = register(email("otra"), "Otra Org");
        String pending = field(send("POST", "/api/v1/team/invitations", owner,
                "{\"email\":\"" + email("pendiente") + "\",\"role\":\"VIEWER\"}").body(), "id");
        assertEquals(404, send("DELETE", "/api/v1/team/invitations/" + pending, otherOwner, null).statusCode(),
                "otra organización no toca las invitaciones ajenas");
        assertEquals(204, send("DELETE", "/api/v1/team/invitations/" + pending, owner, null).statusCode());
    }

    @Test
    @DisplayName("equipo: una cuenta existente acepta con su contraseña y entra a la organización que la invitó")
    void inviteExistingAccount() throws Exception {
        String owner = register(email("duena"), "Fundación Anfitriona");
        String luis = email("luis");
        register(luis, "Contaduría Luis");

        assertEquals(201, send("POST", "/api/v1/team/invitations", owner,
                "{\"email\":\"" + luis + "\",\"role\":\"ADMIN\"}").statusCode());
        String token = awaitToken(luis, "/invitacion", 0);
        assertEquals("true", field(send("POST", "/api/v1/auth/invitations/preview", null,
                "{\"token\":\"" + token + "\"}").body(), "existingAccount"));

        assertEquals(401, send("POST", "/api/v1/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"no es la clave\"}").statusCode());
        HttpResponse<String> accepted = send("POST", "/api/v1/auth/invitations/accept", null,
                "{\"token\":\"" + token + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, accepted.statusCode(), accepted.body());
        String session = field(accepted.body(), "accessToken");
        HttpResponse<String> me = send("GET", "/api/v1/auth/me", session, null);
        assertEquals("Fundación Anfitriona", field(me.body(), "organizationName"));
        assertEquals("ADMIN", field(me.body(), "role"));
        assertEquals("ORGANIZATION_REQUIRED", field(login(luis, PASSWORD).body(), "status"), "ahora tiene dos organizaciones");
    }
}
