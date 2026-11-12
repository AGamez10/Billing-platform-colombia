package co.billingplatform.shared.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens opacos de alta entropía (refresh tokens, claves de API, enlaces de un documento). Al tener 256 bits
 * aleatorios, un SHA-256 sin sal es suficiente para almacenarlos: no aplica fuerza bruta
 * por diccionario como en contraseñas. Nunca se guarda el valor en claro.
 */
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {}

    public static String randomBase64Url(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Comparación en tiempo constante. */
    public static boolean matches(byte[] expectedHash, String presented) {
        return MessageDigest.isEqual(expectedHash, sha256(presented));
    }
}
