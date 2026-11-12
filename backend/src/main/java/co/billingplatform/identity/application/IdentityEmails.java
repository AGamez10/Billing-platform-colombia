package co.billingplatform.identity.application;

import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.application.EmailHtml;
import co.billingplatform.shared.application.Mailer;

import java.util.Objects;

/**
 * Textos de los correos de cuenta. Todo dato que viene del usuario (nombres) se escapa en la
 * versión HTML. Los enlaces llevan el token en el fragmento ({@code #}), que el navegador no
 * envía al servidor ni a terceros: no queda en registros de acceso ni en cabeceras Referer.
 */
public final class IdentityEmails {

    private final String brand;
    private final String baseUrl;

    public IdentityEmails(String brand, String baseUrl) {
        this.brand = Objects.requireNonNull(brand);
        String url = Objects.requireNonNull(baseUrl).strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public Mailer.Email passwordReset(String to, String token, int minutesValid) {
        String link = baseUrl + "/restablecer#t=" + token;
        String text = """
                Hola,

                Recibimos una solicitud para cambiar la contraseña de tu cuenta en %s.
                Para elegir una nueva, abre este enlace (vence en %d minutos y sirve una sola vez):

                %s

                Si no fuiste tú, ignora este correo: tu contraseña sigue igual.
                """.formatted(brand, minutesValid, link);
        String html = layout("Cambia tu contraseña",
                "<p>Recibimos una solicitud para cambiar la contraseña de tu cuenta en " + esc(brand) + ".</p>"
                        + button(link, "Elegir nueva contraseña")
                        + "<p class=\"muted\">El enlace vence en " + minutesValid + " minutos y sirve una sola vez. "
                        + "Si no fuiste tú, ignora este correo: tu contraseña sigue igual.</p>");
        return new Mailer.Email(to, "Cambia tu contraseña de " + brand, text, html);
    }

    public Mailer.Email passwordChanged(String to) {
        String text = """
                Hola,

                La contraseña de tu cuenta en %s acaba de cambiar y cerramos tus sesiones abiertas.
                Si no fuiste tú, escríbenos de inmediato respondiendo este correo.
                """.formatted(brand);
        String html = layout("Tu contraseña cambió",
                "<p>La contraseña de tu cuenta en " + esc(brand) + " acaba de cambiar y cerramos tus sesiones abiertas.</p>"
                        + "<p>Si no fuiste tú, escríbenos de inmediato respondiendo este correo.</p>");
        return new Mailer.Email(to, "Tu contraseña de " + brand + " cambió", text, html);
    }

    public Mailer.Email invitation(String to, String token, String organizationName, String inviterName, Role role,
                                   int daysValid) {
        String link = baseUrl + "/invitacion#t=" + token;
        String who = inviterName == null || inviterName.isBlank() ? "El equipo de " + organizationName : inviterName;
        String text = """
                Hola,

                %s te invitó a unirte a %s en %s como %s.
                Para aceptar, abre este enlace (vence en %d días):

                %s

                Si no esperabas esta invitación, ignora este correo.
                """.formatted(who, organizationName, brand, roleLabel(role), daysValid, link);
        String html = layout("Te invitaron a " + esc(organizationName),
                "<p><strong>" + esc(who) + "</strong> te invitó a unirte a <strong>" + esc(organizationName)
                        + "</strong> en " + esc(brand) + " como <strong>" + roleLabel(role) + "</strong>.</p>"
                        + "<p>" + roleDescription(role) + "</p>"
                        + button(link, "Aceptar invitación")
                        + "<p class=\"muted\">El enlace vence en " + daysValid + " días. Si no esperabas esta invitación, ignora este correo.</p>");
        // El asunto lleva el nombre de la organización: se limpia de saltos de línea.
        String org = organizationName.replaceAll("[\\r\\n]+", " ");
        return new Mailer.Email(to, "Te invitaron a " + org + " en " + brand, text, html);
    }

    public static String roleLabel(Role role) {
        return switch (role) {
            case OWNER -> "dueño";
            case ADMIN -> "administrador";
            case BILLER -> "facturador";
            case VIEWER -> "consulta";
        };
    }

    private static String roleDescription(Role role) {
        return switch (role) {
            case OWNER, ADMIN -> "Podrás emitir documentos, administrar el equipo y la suscripción.";
            case BILLER -> "Podrás vender, emitir facturas y administrar clientes y productos.";
            case VIEWER -> "Podrás consultar facturas, clientes y productos, sin modificarlos.";
        };
    }

    private String layout(String title, String body) {
        return EmailHtml.layout(brand, title, body);
    }

    private static String button(String link, String label) {
        return EmailHtml.button(link, label);
    }

    static String esc(String value) {
        return EmailHtml.esc(value);
    }
}
