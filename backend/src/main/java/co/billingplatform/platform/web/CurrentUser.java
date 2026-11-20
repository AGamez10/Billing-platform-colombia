package co.billingplatform.platform.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quién hace la petición: un usuario (rol de su membresía) o una clave de API (permisos
 * explícitos, nunca administrativos). La organización sale siempre de la credencial verificada.
 *
 * @param userId id del usuario o, para una clave de API, id de la clave (para registrar autoría)
 * @param role   {@code null} si es una clave de API
 * @param scopes {@code null} si es un usuario
 */
public record CurrentUser(UUID userId, TenantId tenantId, Role role, Set<Permission> scopes) {

    /** Claim que solo pone el decodificador de claves de API (los JWT de usuario nunca lo llevan). */
    public static final String API_KEY_CLAIM = "akey";
    public static final String SCOPES_CLAIM = "scp";

    public static CurrentUser from(Jwt jwt) {
        if (jwt == null) {
            throw new DomainException("UNAUTHENTICATED", "Authentication required");
        }
        UUID subject = UUID.fromString(jwt.getSubject());
        TenantId tenant = new TenantId(UUID.fromString(jwt.getClaimAsString("tid")));
        if (Boolean.TRUE.equals(jwt.getClaim(API_KEY_CLAIM))) {
            List<String> scopes = jwt.getClaimAsStringList(SCOPES_CLAIM);
            Set<Permission> permissions = EnumSet.noneOf(Permission.class);
            if (scopes != null) scopes.forEach(s -> permissions.add(Permission.valueOf(s)));
            return new CurrentUser(subject, tenant, null, Set.copyOf(permissions));
        }
        return new CurrentUser(subject, tenant, Role.valueOf(jwt.getClaimAsString("role")), null);
    }

    public boolean isApiKey() {
        return role == null;
    }

    public boolean can(Permission permission) {
        return isApiKey() ? scopes.contains(permission) : role.can(permission);
    }

    /** @throws DomainException {@code FORBIDDEN} si el rol o la clave no tiene el permiso */
    public CurrentUser require(Permission permission) {
        if (!can(permission)) {
            throw new DomainException("FORBIDDEN", isApiKey() ? "This API key does not have the " + permission + " scope"
                    : "Your role does not allow this operation");
        }
        return this;
    }

    /** Operaciones que solo hace una persona (equipo, plan, claves): una clave de API no. */
    public CurrentUser requireUser() {
        if (isApiKey()) {
            throw new DomainException("FORBIDDEN", "This operation requires a user session");
        }
        return this;
    }

    public Actor actor() {
        return isApiKey() ? Actor.apiKey(userId) : Actor.user(userId);
    }
}
