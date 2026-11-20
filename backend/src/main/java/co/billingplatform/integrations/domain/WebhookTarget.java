package co.billingplatform.integrations.domain;

import co.billingplatform.shared.domain.DomainException;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Validación estática de la URL de un webhook saliente (antes de guardarla): solo HTTPS, con
 * nombre de host público (no IP literal, no localhost ni dominios internos), sin credenciales ni
 * fragmento. La resolución DNS se vuelve a comprobar en cada envío (protección SSRF).
 */
public final class WebhookTarget {

    private WebhookTarget() {}

    public static URI validate(String url) {
        if (url == null || url.isBlank() || url.length() > 500) {
            throw invalid("URL is required (max 500 characters)");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw invalid("URL is not valid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw invalid("Only https:// URLs are allowed");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid("URL must not contain credentials or a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("URL must have a host");
        }
        String h = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        if (h.matches("[0-9.]+") || h.contains(":") || h.startsWith("[")) {
            throw invalid("Use a domain name, not an IP address");
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains(".")) {
            throw invalid("The host must be a public domain");
        }
        int port = uri.getPort();
        if (port != -1 && port != 443 && port != 8443) {
            throw invalid("Only ports 443 and 8443 are allowed");
        }
        return uri;
    }

    private static DomainException invalid(String message) {
        return new DomainException("INVALID_WEBHOOK_URL", message);
    }
}
