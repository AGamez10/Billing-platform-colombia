package co.billingplatform.payments.adapter.bold;

import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.payments.port.MerchantPaymentGateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Adaptador de Bold: API Link de pagos (developers.bold.co, consultado el 2026-11-06).
 *
 * <ul>
 *   <li>Autenticación: cabecera {@code Authorization: x-api-key <llave de identidad>}.</li>
 *   <li>Crear: {@code POST /online/link/v1} con {@code amount_type=CLOSE} y el total exacto. El
 *       checkout de Bold ofrece los medios habilitados en la cuenta del comercio (Bre-B por QR,
 *       Nequi, PSE, tarjeta).</li>
 *   <li>Estado: {@code GET /online/link/v1/{id}}: ACTIVE, PROCESSING, PAID, REJECTED, CANCELLED,
 *       EXPIRED.</li>
 *   <li>Webhook: cabecera {@code x-bold-signature} = HMAC-SHA256 en hexadecimal del cuerpo en
 *       Base64, con la llave secreta (vacía en pruebas).</li>
 * </ul>
 *
 * <p>Bold distingue pruebas y producción por la llave usada, no por la URL.
 */
public final class BoldGateway implements MerchantPaymentGateway {

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    public BoldGateway(URI baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public PaymentAccount.Gateway gateway() {
        return PaymentAccount.Gateway.BOLD;
    }

    @Override
    public List<String> verifyCredentials(Credentials credentials) {
        Object body = call(credentials, HttpRequest.newBuilder(uri("/online/link/v1/payment_methods")).GET());
        List<String> methods = new ArrayList<>();
        Object payload = BoldJson.path(body, "payload");
        Object list = payload instanceof Map<?, ?> m && m.get("payment_methods") != null ? m.get("payment_methods") : payload;
        if (list instanceof List<?> l) {
            for (Object o : l) {
                String name = o instanceof Map<?, ?> m ? BoldJson.text(m, "name") : String.valueOf(o);
                if (name != null) methods.add(name);
            }
        } else if (list instanceof Map<?, ?> m) {
            m.keySet().forEach(k -> methods.add(String.valueOf(k)));
        }
        return methods;
    }

    @Override
    public CreatedLink createLink(Credentials credentials, String reference, BigDecimal amount, String description) {
        BigDecimal pesos = amount.setScale(0, RoundingMode.HALF_UP);
        String json = "{\"amount_type\":\"CLOSE\",\"amount\":{\"currency\":\"COP\",\"total_amount\":" + pesos.toPlainString()
                + ",\"tip_amount\":0},\"reference\":" + BoldJson.quote(reference)
                + ",\"description\":" + BoldJson.quote(description) + "}";
        Object body = call(credentials, HttpRequest.newBuilder(uri("/online/link/v1"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
        String id = BoldJson.text(body, "payload", "payment_link");
        String url = BoldJson.text(body, "payload", "url");
        if (id == null || url == null || !url.startsWith("https://")) {
            throw new GatewayException("Bold did not return a payment link", false);
        }
        return new CreatedLink(id, url);
    }

    @Override
    public LinkStatus status(Credentials credentials, String linkId) {
        if (!linkId.matches("[A-Za-z0-9_-]{1,60}")) {
            throw new GatewayException("Invalid link id", false);
        }
        Object body = call(credentials, HttpRequest.newBuilder(uri("/online/link/v1/" + URLEncoder.encode(linkId, StandardCharsets.UTF_8))).GET());
        Object root = BoldJson.path(body, "payload") instanceof Map<?, ?> p ? p : body;
        String status = BoldJson.text(root, "status");
        return new LinkStatus(map(status), BoldJson.text(root, "transaction_id"), BoldJson.text(root, "payment_method"));
    }

    @Override
    public boolean verifyWebhook(Credentials credentials, byte[] body, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = credentials.secretKey().getBytes(StandardCharsets.UTF_8);
            // HMAC admite llave vacía (modo pruebas de Bold); SecretKeySpec no, por eso se usa un byte nulo equivalente.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[] {0} : key, "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(Base64.getEncoder().encode(body)))
                    .getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signature.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static Charge.Status map(String boldStatus) {
        if (boldStatus == null) return Charge.Status.PENDING;
        return switch (boldStatus.toUpperCase()) {
            case "PAID", "APPROVED" -> Charge.Status.PAID;
            case "REJECTED", "FAILED" -> Charge.Status.REJECTED;
            case "EXPIRED" -> Charge.Status.EXPIRED;
            case "CANCELLED", "CANCELED" -> Charge.Status.CANCELLED;
            default -> Charge.Status.PENDING; // ACTIVE, PROCESSING o desconocido: seguir esperando
        };
    }

    private URI uri(String path) {
        return baseUrl.resolve(baseUrl.getPath().replaceAll("/$", "") + path);
    }

    private Object call(Credentials credentials, HttpRequest.Builder request) {
        HttpRequest req = request
                .timeout(timeout)
                .header("Authorization", "x-api-key " + credentials.identityKey())
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GatewayException("Bold unreachable: " + e.getClass().getSimpleName(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Interrupted", false);
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new GatewayException("Bold rejected the credentials (HTTP " + res.statusCode() + ")", true);
        }
        if (res.statusCode() >= 300) {
            throw new GatewayException("Bold answered HTTP " + res.statusCode(), false);
        }
        try {
            return BoldJson.parse(res.body());
        } catch (IllegalArgumentException e) {
            throw new GatewayException("Bold answered invalid JSON", false);
        }
    }
}
