package co.billingplatform.platform.crypto;

import co.billingplatform.identity.adapter.crypto.AesGcmSecretSealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmSecretSealerTest {

    // Llave SOLO de pruebas (la misma de PostgresIntegrationTest); en producción viene de BILLING_SECRETS_KEY.
    private static final String KEY = "dGVzdC1vbmx5LXNlY3JldHMta2V5LTMyLWJ5dGVzISE="; // gitleaks:allow

    @Test
    @DisplayName("el secreto TOTP se cifra con nonce aleatorio y solo lo abre el mismo usuario")
    void sealAndOpen() {
        AesGcmSecretSealer sealer = new AesGcmSecretSealer(KEY);
        UUID ana = UUID.randomUUID();
        byte[] secret = "12345678901234567890".getBytes();
        byte[] a = sealer.seal(ana, secret);
        byte[] b = sealer.seal(ana, secret);
        assertFalse(Arrays.equals(a, b), "nonce distinto en cada cifrado");
        assertTrue(Arrays.equals(secret, sealer.open(ana, a)));
        assertThrows(IllegalStateException.class, () -> sealer.open(UUID.randomUUID(), a), "copiado a otro usuario no abre");
        byte[] tampered = a.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> sealer.open(ana, tampered));
        assertThrows(IllegalStateException.class,
                () -> new AesGcmSecretSealer("c2hvcnQ=").seal(ana, secret), "llave de 32 bytes obligatoria");
    }
}
