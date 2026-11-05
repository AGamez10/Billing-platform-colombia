package co.billingplatform.electronicinvoicing.adapter.common;

import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Verificación HMAC-SHA256 reutilizable por adaptadores cuyo proveedor firme webhooks
 * con un secreto compartido. Firma = HMAC(secreto, timestamp + "." + cuerpo).
 *
 * <p>Protege contra webhooks falsificados (firma), manipulados (el cuerpo está firmado)
 * y reproducidos fuera de ventana (timestamp con tolerancia). La deduplicación de
 * eventos repetidos dentro de la ventana la hace el núcleo por {@code eventId}.
 * El esquema concreto de cada proveedor real se confirma con su documentación.
 */
public final class HmacWebhookVerifier {

    private final String signatureHeader;
    private final String timestampHeader;
    private final Duration tolerance;

    public HmacWebhookVerifier(String signatureHeader, String timestampHeader, Duration tolerance) {
        this.signatureHeader = Objects.requireNonNull(signatureHeader);
        this.timestampHeader = Objects.requireNonNull(timestampHeader);
        this.tolerance = Objects.requireNonNull(tolerance);
    }

    public void verify(InboundWebhook webhook, byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new WebhookVerificationException("webhook secret missing or too short");
        }
        String timestamp = webhook.header(timestampHeader)
                .orElseThrow(() -> new WebhookVerificationException("missing timestamp"));
        String signature = webhook.header(signatureHeader)
                .orElseThrow(() -> new WebhookVerificationException("missing signature"));

        Instant sentAt;
        try {
            sentAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException("malformed timestamp");
        }
        if (Duration.between(sentAt, webhook.receivedAt()).abs().compareTo(tolerance) > 0) {
            throw new WebhookVerificationException("timestamp outside tolerance");
        }

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("malformed signature");
        }
        byte[] expected = sign(secret, timestamp, webhook.body());
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new WebhookVerificationException("signature mismatch");
        }
    }

    public static byte[] sign(byte[] secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
