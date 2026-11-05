package co.billingplatform.platform.security;

import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.TenantId;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Access tokens JWT de corta duración (15 min) firmados HS256 con un secreto de al menos
 * 256 bits que llega por variable de entorno. Claims: sub (usuario), tid (organización), role.
 */
@Configuration
public class AccessTokens {

    public static final Duration TTL = Duration.ofMinutes(15);
    public static final String ISSUER = "billing-platform";

    public record Issued(String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    private final SecretKey key;
    private final JwtEncoder encoder;
    private final Clock clock;

    public AccessTokens(@Value("${billing.security.jwt-secret}") String base64Secret, Clock clock) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Secret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("billing.security.jwt-secret must be base64", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("billing.security.jwt-secret must have at least 256 bits");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.clock = clock;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    public Issued issue(UUID userId, TenantId tenantId, Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TTL);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("tid", tenantId.toString())
                .claim("role", role.name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new Issued(token, expiresAt);
    }
}
