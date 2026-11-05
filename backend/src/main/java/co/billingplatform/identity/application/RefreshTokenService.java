package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.Memberships;
import co.billingplatform.identity.application.IdentityPorts.RefreshTokenRecord;
import co.billingplatform.identity.application.IdentityPorts.RefreshTokens;
import co.billingplatform.identity.domain.OpaqueTokens;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh tokens opacos con rotación y detección de reúso (ADR-0003).
 *
 * <p>Cada uso entrega un token nuevo y gasta el anterior. Si un token ya gastado vuelve a
 * presentarse, alguien más lo tiene (robo o fuga): se revoca la familia completa y ambas
 * partes deben volver a iniciar sesión. El rol se relee en cada rotación, así quitar a un
 * usuario de una organización corta su sesión a más tardar al expirar el access token.
 */
public final class RefreshTokenService {

    public static final Duration TTL = Duration.ofDays(14);

    public record Issued(String token, UUID userId, TenantId tenantId, Role role, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[userId=%s, tenant=%s, role=%s, expiresAt=%s]".formatted(userId, tenantId, role, expiresAt);
        }
    }

    private final RefreshTokens tokens;
    private final Memberships memberships;
    private final Clock clock;

    public RefreshTokenService(RefreshTokens tokens, Memberships memberships, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens);
        this.memberships = Objects.requireNonNull(memberships);
        this.clock = Objects.requireNonNull(clock);
    }

    public Issued issue(UUID userId, TenantId tenantId) {
        Role role = currentRole(userId, tenantId);
        return store(userId, tenantId, role, UUID.randomUUID());
    }

    public Issued rotate(String presentedToken) {
        Instant now = clock.instant();
        if (presentedToken == null || presentedToken.length() > 200) {
            throw invalid();
        }
        RefreshTokenRecord current = tokens.findByHash(OpaqueTokens.sha256(presentedToken)).orElseThrow(RefreshTokenService::invalid);

        if (current.isSpent()) {
            tokens.revokeFamily(current.familyId(), now);
            throw new DomainException("REFRESH_TOKEN_REUSED", "Session revoked, please sign in again");
        }
        if (!current.expiresAt().isAfter(now)) {
            throw invalid();
        }
        Role role = currentRole(current.userId(), current.tenantId());

        UUID nextId = UUID.randomUUID();
        if (!tokens.markReplaced(current.id(), nextId, now)) {
            // Otra petición lo gastó entre la lectura y ahora: mismo tratamiento que un reúso.
            tokens.revokeFamily(current.familyId(), now);
            throw new DomainException("REFRESH_TOKEN_REUSED", "Session revoked, please sign in again");
        }
        return store(current.userId(), current.tenantId(), role, current.familyId(), nextId);
    }

    public void revoke(String presentedToken) {
        if (presentedToken == null || presentedToken.length() > 200) {
            return;
        }
        tokens.findByHash(OpaqueTokens.sha256(presentedToken))
                .ifPresent(t -> tokens.revokeFamily(t.familyId(), clock.instant()));
    }

    private Role currentRole(UUID userId, TenantId tenantId) {
        return memberships.activeFor(userId).stream()
                .filter(m -> m.tenantId().equals(tenantId))
                .map(IdentityPorts.Membership::role)
                .findFirst()
                .orElseThrow(RefreshTokenService::invalid);
    }

    private Issued store(UUID userId, TenantId tenantId, Role role, UUID familyId) {
        return store(userId, tenantId, role, familyId, UUID.randomUUID());
    }

    private Issued store(UUID userId, TenantId tenantId, Role role, UUID familyId, UUID id) {
        String raw = OpaqueTokens.randomBase64Url(32);
        Instant expiresAt = clock.instant().plus(TTL);
        tokens.insert(new RefreshTokenRecord(id, userId, tenantId, familyId, OpaqueTokens.sha256(raw),
                expiresAt, null, null));
        return new Issued(raw, userId, tenantId, role, expiresAt);
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_REFRESH_TOKEN", "Session expired, please sign in again");
    }
}
