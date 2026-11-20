package co.billingplatform.integrations.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Firma de los avisos salientes: {@code X-Billing-Signature: t=<epoch>,v1=<hex>} donde
 * {@code v1 = HMAC-SHA256(secreto, "<epoch>.<cuerpo>")}. El receptor recalcula, compara en tiempo
 * constante y rechaza marcas de tiempo viejas (p. ej. más de 5 minutos) para evitar repeticiones.
 */
public final class WebhookSignature {

    private WebhookSignature() {}

    public static String header(String secret, long epochSeconds, String body) {
        return "t=" + epochSeconds + ",v1=" + hmac(secret, epochSeconds + "." + body);
    }

    public static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
