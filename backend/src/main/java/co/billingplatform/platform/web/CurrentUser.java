package co.billingplatform.platform.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Principal autenticado tomado del access token. Es la ÚNICA fuente del tenant de una petición:
 * ningún controlador acepta un id de organización desde la URL, cabeceras o cuerpo.
 */
public record CurrentUser(UUID userId, TenantId tenantId, Role role) {

    public static CurrentUser from(Jwt jwt) {
        if (jwt == null) {
            throw new DomainException("UNAUTHENTICATED", "Authentication required");
        }
        return new CurrentUser(UUID.fromString(jwt.getSubject()),
                new TenantId(UUID.fromString(jwt.getClaimAsString("tid"))),
                Role.valueOf(jwt.getClaimAsString("role")));
    }

    /** @throws DomainException {@code FORBIDDEN} si el rol no tiene el permiso */
    public CurrentUser require(Permission permission) {
        if (!role.can(permission)) {
            throw new DomainException("FORBIDDEN", "Your role does not allow this operation");
        }
        return this;
    }

    public Actor actor() {
        return Actor.user(userId);
    }
}
