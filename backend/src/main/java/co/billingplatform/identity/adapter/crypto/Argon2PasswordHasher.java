package co.billingplatform.identity.adapter.crypto;

import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.shared.domain.OpaqueTokens;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id con los parámetros recomendados por Spring Security 5.8+ (16 MiB, 2 iteraciones).
 * El hash ficticio se calcula una vez al arrancar para igualar tiempos en el login.
 */
@Component
class Argon2PasswordHasher implements IdentityPorts.PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = encoder.encode(OpaqueTokens.randomBase64Url(24));

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedHash) {
        return encodedHash != null && encoder.matches(rawPassword, encodedHash);
    }

    @Override
    public String dummyHash() {
        return dummyHash;
    }
}
