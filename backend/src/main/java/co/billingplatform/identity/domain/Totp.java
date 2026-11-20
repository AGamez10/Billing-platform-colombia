package co.billingplatform.identity.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Códigos de un solo uso basados en tiempo (TOTP, RFC 6238) con los parámetros que entienden
 * todas las apps de autenticación (Google Authenticator, Microsoft Authenticator, 1Password,
 * Aegis…): HMAC-SHA1, 6 dígitos, pasos de 30 segundos.
 *
 * <p>Se acepta el paso actual y uno a cada lado (±30 s) por desfase de reloj del teléfono. Un
 * código solo vale si su paso es posterior al último usado: el mismo código no sirve dos veces
 * aunque siga en la ventana (antirreplay).
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int SECRET_BYTES = 20;
    static final int WINDOW = 1;

    private Totp() {}

    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
    }

    /** RFC 4226 §5.3 (truncamiento dinámico) sobre el contador {@code step}. */
    public static String code(byte[] secret, long step) {
        Objects.requireNonNull(secret);
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (step & 0xff);
            step >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            hash = mac.doFinal(counter);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
    }

    /**
     * @param lastUsedStep último paso aceptado para este secreto (0 si nunca)
     * @return el paso que coincide, o vacío si el código no es válido o ya se usó
     */
    public static OptionalLong verify(byte[] secret, String presented, Instant now, long lastUsedStep) {
        String code = normalize(presented);
        if (code == null) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        long matched = -1;
        // Se recorren todos los pasos de la ventana para que el tiempo no dependa de cuál coincide.
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            boolean equal = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII));
            if (equal && step > lastUsedStep && matched < 0) {
                matched = step;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    /** Acepta "123 456" o "123-456"; devuelve null si no son 6 dígitos. */
    public static String normalize(String presented) {
        if (presented == null) {
            return null;
        }
        String digits = presented.replaceAll("[\\s-]", "");
        return digits.length() == DIGITS && digits.chars().allMatch(c -> c >= '0' && c <= '9') ? digits : null;
    }

    /** URI {@code otpauth://} que las apps leen desde el código QR. */
    public static String provisioningUri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
