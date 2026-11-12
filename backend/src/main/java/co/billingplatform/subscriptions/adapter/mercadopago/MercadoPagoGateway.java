package co.billingplatform.subscriptions.adapter.mercadopago;

import co.billingplatform.shared.json.Json;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway;

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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Mercado Pago para cobrar NUESTROS planes: Checkout Pro (preferencia de pago) y consulta de
 * pagos por id y por referencia externa. Cliente HTTP del JDK, sin SDK.
 *
 * <p>Firma de avisos: cabecera {@code x-signature: ts=…,v1=…}; {@code v1} es el HMAC-SHA256 en
 * hexadecimal del manifiesto {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;} con la clave
 * secreta de los webhooks (las partes que no llegan se omiten). Aun con firma válida, el cuerpo
 * del aviso no se cree: el pago se consulta a la API.
 */
public final class MercadoPagoGateway implements PaymentGateway {

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneOffset.UTC);

    private final HttpClient http;
    private final URI baseUrl;
    private final String accessToken;
    private final byte[] webhookSecret;
    private final Duration timeout;

    public MercadoPagoGateway(URI baseUrl, String accessToken, String webhookSecret, Duration timeout) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Mercado Pago access token is required");
        }
        this.accessToken = accessToken.strip();
        this.webhookSecret = webhookSecret == null || webhookSecret.isBlank() ? null : webhookSecret.strip().getBytes(StandardCharsets.UTF_8);
        this.timeout = Objects.requireNonNull(timeout);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Preference createPreference(Request r) {
        String amount = r.amount().setScale(0, RoundingMode.HALF_UP).toPlainString(); // COP sin decimales
        String body = "{"
                + "\"items\":[{\"id\":" + Json.quote(r.externalReference()) + ",\"title\":" + Json.quote(r.title())
                + ",\"quantity\":1,\"currency_id\":\"COP\",\"unit_price\":" + amount + "}],"
                + "\"external_reference\":" + Json.quote(r.externalReference()) + ","
                + "\"notification_url\":" + Json.quote(r.notificationUrl().toString()) + ","
                + "\"back_urls\":{\"success\":" + Json.quote(r.successUrl().toString())
                + ",\"pending\":" + Json.quote(r.pendingUrl().toString())
                + ",\"failure\":" + Json.quote(r.failureUrl().toString()) + "},"
                + "\"auto_return\":\"approved\","
                + "\"statement_descriptor\":\"SUSCRIPCION\","
                + "\"expires\":true,"
                + "\"expiration_date_to\":" + Json.quote(ISO_MILLIS.format(r.expiresAt()))
                + "}";
        Object json = send(HttpRequest.newBuilder(baseUrl.resolve("/checkout/preferences"))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", r.externalReference()));
        String id = Json.text(json, "id");
        String url = Json.text(json, "init_point");
        if (id == null || url == null || !url.startsWith("https://")) {
            throw new GatewayException("Unexpected preference response");
        }
        return new Preference(id, url);
    }

    @Override
    public Optional<Payment> payment(String paymentId) {
        if (paymentId == null || !paymentId.matches("[0-9]{1,30}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(toPayment(send(HttpRequest.newBuilder(baseUrl.resolve("/v1/payments/" + paymentId)).GET())));
        } catch (NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Payment> paymentsFor(String externalReference) {
        String query = "/v1/payments/search?sort=date_created&criteria=desc&external_reference="
                + URLEncoder.encode(externalReference, StandardCharsets.UTF_8);
        Object json = send(HttpRequest.newBuilder(baseUrl.resolve(query)).GET());
        List<Payment> payments = new ArrayList<>();
        if (Json.path(json, "results") instanceof List<?> results) {
            for (Object result : results) {
                payments.add(toPayment(result));
            }
        }
        return payments;
    }

    @Override
    public boolean verifyWebhook(String dataId, String requestId, String signatureHeader) {
        if (webhookSecret == null || signatureHeader == null || signatureHeader.length() > 500) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].strip();
            if (key.equals("ts")) ts = kv[1].strip();
            if (key.equals("v1")) v1 = kv[1].strip();
        }
        if (ts == null || v1 == null || !v1.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            // Los ids alfanuméricos van en minúsculas en el manifiesto (documentación de Mercado Pago).
            manifest.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId).append(';');
        }
        manifest.append("ts:").append(ts).append(';');
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            byte[] expected = mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1.toLowerCase(Locale.ROOT)));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static PaymentStatus map(String status) {
        if (status == null) return PaymentStatus.PENDING;
        return switch (status) {
            case "approved" -> PaymentStatus.APPROVED;
            case "rejected", "cancelled" -> PaymentStatus.REJECTED;
            case "refunded", "charged_back" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.PENDING; // pending, in_process, authorized, in_mediation y lo desconocido
        };
    }

    private static Payment toPayment(Object json) {
        Object id = Json.path(json, "id");
        Object amount = Json.path(json, "transaction_amount");
        Object live = Json.path(json, "live_mode");
        return new Payment(
                id == null ? null : (id instanceof BigDecimal n ? n.toPlainString() : id.toString()),
                map(Json.text(json, "status")),
                Json.text(json, "external_reference"),
                amount instanceof BigDecimal n ? n : null,
                Json.text(json, "currency_id"),
                Boolean.TRUE.equals(live));
    }

    private static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private Object send(HttpRequest.Builder builder) {
        HttpRequest request = builder.timeout(timeout)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 404) throw new NotFound();
            if (status == 401 || status == 403) throw new GatewayException("Mercado Pago rejected the credentials");
            if (status < 200 || status >= 300) throw new GatewayException("Mercado Pago answered " + status);
            return Json.parse(response.body());
        } catch (IOException e) {
            throw new GatewayException("Mercado Pago unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Interrupted");
        } catch (IllegalArgumentException e) {
            throw new GatewayException("Unreadable Mercado Pago response");
        }
    }

    /** Para pruebas: nada de credenciales en registros. */
    @Override
    public String toString() {
        return "MercadoPagoGateway[" + baseUrl + "]";
    }
}
