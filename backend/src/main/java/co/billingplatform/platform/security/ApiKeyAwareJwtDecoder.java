package co.billingplatform.platform.security;

import co.billingplatform.identity.application.ApiKeyService;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.shared.domain.DomainException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code Authorization: Bearer bpk_…} se verifica como clave de API (hash, vigencia, organización
 * activa) y se convierte en un principal interno con la organización y los permisos de la clave;
 * cualquier otro token se valida como JWT firmado. Así todos los controladores usan
 * {@link CurrentUser} sin saber cómo se autenticó la petición.
 */
final class ApiKeyAwareJwtDecoder implements JwtDecoder {

    private final JwtDecoder jwt;
    private final ApiKeyService apiKeys;
    private final Clock clock;

    ApiKeyAwareJwtDecoder(JwtDecoder jwt, ApiKeyService apiKeys, Clock clock) {
        this.jwt = Objects.requireNonNull(jwt);
        this.apiKeys = Objects.requireNonNull(apiKeys);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        if (token == null || !token.startsWith("bpk_")) {
            return jwt.decode(token);
        }
        ApiKeyService.ApiKeyPrincipal principal;
        try {
            principal = apiKeys.authenticate(token);
        } catch (DomainException e) {
            throw new BadJwtException("Invalid API key");
        }
        Instant now = clock.instant();
        return Jwt.withTokenValue("api-key") // nunca se guarda el secreto en el principal
                .header("alg", "none")
                .subject(principal.keyId().toString())
                .claim("tid", principal.tenantId().value().toString())
                .claim(CurrentUser.API_KEY_CLAIM, true)
                .claim(CurrentUser.SCOPES_CLAIM, principal.scopes().stream().map(Enum::name).sorted().toList())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
