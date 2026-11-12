package co.billingplatform.shared.application;

/** Plantilla HTML común de los correos (compatible con clientes de correo: tablas y estilos en línea). */
public final class EmailHtml {

    private EmailHtml() {}

    /** {@code title} y {@code body} ya deben venir escapados; {@code brand} se escapa aquí. */
    public static String layout(String brand, String title, String body) {
        return """
                <!doctype html><html lang="es"><body style="margin:0;background:#f6f7f9;font-family:Arial,Helvetica,sans-serif;color:#0e1a2b">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:32px 16px">
                <table role="presentation" width="100%%" style="max-width:520px;background:#ffffff;border-radius:14px;padding:32px" cellpadding="0" cellspacing="0"><tr><td>
                <p style="margin:0 0 24px;font-weight:bold;color:#0b7a5c">%s</p>
                <h1 style="margin:0 0 16px;font-size:22px;line-height:1.3">%s</h1>
                <div style="font-size:15px;line-height:1.6">%s</div>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(esc(brand), title, body.replace("class=\"muted\"", "style=\"color:#5b6b80;font-size:13px\""));
    }

    public static String button(String link, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"" + esc(link) + "\" style=\"background:#0b7a5c;color:#ffffff;"
                + "text-decoration:none;padding:12px 20px;border-radius:10px;display:inline-block;font-weight:bold\">"
                + label + "</a></p><p style=\"color:#5b6b80;font-size:13px;word-break:break-all\">Si el botón no funciona, copia este enlace: "
                + esc(link) + "</p>";
    }

    public static String esc(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Para asuntos y encabezados: sin saltos de línea (evita inyección de cabeceras). */
    public static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }
}
