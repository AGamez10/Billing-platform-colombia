package co.billingplatform.identity.adapter.crypto;

import co.billingplatform.identity.application.TwoFactorPorts;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Secreto TOTP cifrado con AES-256-GCM y la llave maestra {@code BILLING_SECRETS_KEY}.
 * Formato: versión (1 byte) · nonce aleatorio (12) · cifrado + etiqueta. Datos asociados =
 * {@code totp|<usuario>}: el valor copiado a la fila de otro usuario no se descifra.
 */
public final class AesGcmSecretSealer implements TwoFactorPorts.SecretSealer {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretSealer(String base64MasterKey) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(Objects.requireNonNull(base64MasterKey, "BILLING_SECRETS_KEY is required").strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BILLING_SECRETS_KEY must be Base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("BILLING_SECRETS_KEY must decode to 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public byte[] seal(UUID userId, byte[] secret) {
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, userId, secret);
        byte[] out = new byte[1 + NONCE + ciphertext.length];
        out[0] = VERSION;
        System.arraycopy(nonce, 0, out, 1, NONCE);
        System.arraycopy(ciphertext, 0, out, 1 + NONCE, ciphertext.length);
        return out;
    }

    @Override
    public byte[] open(UUID userId, byte[] sealed) {
        if (sealed == null || sealed.length <= 1 + NONCE || sealed[0] != VERSION) {
            throw new IllegalStateException("Unknown sealed secret format");
        }
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(sealed, 1, 1 + NONCE), userId,
                Arrays.copyOfRange(sealed, 1 + NONCE, sealed.length));
    }

    private byte[] crypt(int mode, byte[] nonce, UUID userId, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(("totp|" + userId).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret could not be " + (mode == Cipher.ENCRYPT_MODE ? "encrypted" : "decrypted"));
        }
    }
}
