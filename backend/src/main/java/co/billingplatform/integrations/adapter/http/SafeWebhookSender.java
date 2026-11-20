package co.billingplatform.integrations.adapter.http;

import co.billingplatform.integrations.application.WebhookService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Envía avisos solo a destinos públicos: antes de cada envío resuelve el host y rechaza
 * direcciones de loopback, privadas, link-local (incluido 169.254.169.254, metadatos de la nube),
 * CGNAT, multicast y sin especificar, en IPv4 e IPv6. Sin redirecciones; el cuerpo de la
 * respuesta se descarta. Riesgo residual: un DNS que cambie entre la comprobación y la conexión
 * (DNS rebinding); queda documentado en SECURITY.md.
 */
public final class SafeWebhookSender implements WebhookService.Sender {

    private final HttpClient http;
    private final Duration timeout;

    public SafeWebhookSender(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public int post(URI url, Map<String, String> headers, String body) {
        requirePublic(url.getHost());
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new SendException("Connection failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SendException("Interrupted");
        }
    }

    static void requirePublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SendException("Host not found");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) {
                throw new SendException("Destination is not a public address");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
            if (first == 100 && second >= 64 && second <= 127) return false; // CGNAT 100.64.0.0/10
            if (first == 169 && second == 254) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            return true;
        }
        // IPv6: únicas locales fc00::/7, mapeadas a IPv4 (::ffff:a.b.c.d), documentación 2001:db8::/32
        int first = b[0] & 0xff;
        if ((first & 0xfe) == 0xfc) return false;
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
        if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            try {
                return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return !(first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8);
    }
}
