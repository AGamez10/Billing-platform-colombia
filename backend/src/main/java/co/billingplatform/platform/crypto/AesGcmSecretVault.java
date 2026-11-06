package co.billingplatform.platform.crypto;

import co.billingplatform.electronicinvoicing.port.SecretResolver;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Secretos de cada tenant cifrados en {@code provider_secrets} con AES-256-GCM.
 *
 * <ul>
 *   <li>Llave maestra: {@code BILLING_SECRETS_KEY} (Base64, 32 bytes). Nunca se guarda en la base.</li>
 *   <li>Nonce aleatorio de 96 bits por secreto.</li>
 *   <li>Datos asociados (AAD) = tenant + referencia: un cifrado copiado a otra fila u otra
 *       organización no se descifra.</li>
 *   <li>{@code key_version} permite rotar la llave maestra sin perder lo guardado.</li>
 * </ul>
 * La lectura respeta RLS: solo se ve lo del tenant en contexto.
 */
public final class AesGcmSecretVault implements PaymentPorts.SecretVault, SecretResolver {

    private static final int KEY_VERSION = 1;
    private static final int TAG_BITS = 128;

    private final JdbcClient jdbc;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretVault(JdbcClient jdbc, String base64MasterKey) {
        this.jdbc = Objects.requireNonNull(jdbc);
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
    public void store(TenantId tenantId, String ref, String value) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, aad(tenantId.value(), ref), value.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                        INSERT INTO provider_secrets (tenant_id, ref, ciphertext, nonce, key_version)
                        VALUES (:t, :ref, :c, :n, :v)
                        """)
                .param("t", tenantId.value())
                .param("ref", ref)
                .param("c", ciphertext)
                .param("n", nonce)
                .param("v", KEY_VERSION)
                .update();
    }

    @Override
    public String read(String ref) {
        return new String(resolve(ref), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] resolve(String ref) {
        return jdbc.sql("SELECT tenant_id, ciphertext, nonce, key_version FROM provider_secrets WHERE ref = :ref")
                .param("ref", ref)
                .query((rs, n) -> {
                    if (rs.getInt("key_version") != KEY_VERSION) {
                        throw new IllegalStateException("Unknown secret key version");
                    }
                    return crypt(Cipher.DECRYPT_MODE, rs.getBytes("nonce"),
                            aad(rs.getObject("tenant_id", UUID.class), ref), rs.getBytes("ciphertext"));
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException("Secret not found"));
    }

    private static byte[] aad(UUID tenant, String ref) {
        return (tenant + "|" + ref).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // No se incluye el detalle: nunca debe filtrar información sobre la llave o el contenido.
            throw new IllegalStateException("Secret could not be " + (mode == Cipher.ENCRYPT_MODE ? "encrypted" : "decrypted"));
        }
    }
}
