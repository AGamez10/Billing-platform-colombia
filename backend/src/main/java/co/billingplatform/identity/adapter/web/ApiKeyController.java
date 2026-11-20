package co.billingplatform.identity.adapter.web;

import co.billingplatform.identity.application.ApiKeyService;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Claves de API para integraciones (ADR-0032). Se usan como {@code Authorization: Bearer bpk_…};
 * llevan solo permisos de operación (nunca administrativos) y el secreto se muestra una vez.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "Integraciones")
@SecurityRequirement(name = "bearer")
class ApiKeyController {

    /** Permisos que puede tener una clave (los administrativos los rechaza el servicio). */
    static final Set<Permission> GRANTABLE = EnumSet.of(Permission.INVOICE_READ, Permission.INVOICE_WRITE,
            Permission.INVOICE_FINALIZE, Permission.CUSTOMER_READ, Permission.CUSTOMER_WRITE, Permission.PRODUCT_READ,
            Permission.PRODUCT_WRITE);

    record KeyRequest(@NotBlank @Size(max = 100) String name, @NotEmpty Set<Permission> scopes, @Future Instant expiresAt) {}

    record KeyView(UUID id, String name, String prefix, List<String> scopes, Instant createdAt, Instant lastUsedAt,
                   Instant expiresAt, Instant revokedAt) {}

    record IssuedView(UUID id, String prefix, String key) {}

    private final ApiKeyService keys;
    private final TenantTransactions tx;

    ApiKeyController(ApiKeyService keys, TenantTransactions tx) {
        this.keys = keys;
        this.tx = tx;
    }

    private CurrentUser admin(Jwt jwt) {
        return CurrentUser.from(jwt).requireUser().require(Permission.API_KEYS_MANAGE);
    }

    @GetMapping
    @Operation(summary = "Claves de la organización (sin el secreto)")
    List<KeyView> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = admin(jwt);
        return tx.inTenant(u.tenantId(), () -> keys.list(u.tenantId()).stream()
                .map(k -> new KeyView(k.id(), k.name(), k.prefix(), k.scopes().stream().map(Enum::name).sorted().toList(),
                        k.createdAt(), k.lastUsedAt(), k.expiresAt(), k.revokedAt()))
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear una clave. Permisos posibles: INVOICE_READ, INVOICE_WRITE, INVOICE_FINALIZE, CUSTOMER_READ, "
            + "CUSTOMER_WRITE, PRODUCT_READ, PRODUCT_WRITE. El valor `key` solo se devuelve aquí.")
    IssuedView issue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KeyRequest r) {
        CurrentUser u = admin(jwt);
        if (!GRANTABLE.containsAll(r.scopes())) {
            throw new co.billingplatform.shared.domain.DomainException("INVALID_API_KEY_SCOPES", "Scope not allowed for API keys");
        }
        var issued = tx.inTenant(u.tenantId(), () -> keys.issue(u.tenantId(), u.actor(), r.name(), r.scopes(), r.expiresAt()));
        return new IssuedView(issued.id(), issued.prefix(), issued.plaintext());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revocar una clave: deja de funcionar de inmediato")
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = admin(jwt);
        tx.inTenant(u.tenantId(), () -> {
            keys.revoke(u.tenantId(), u.actor(), id);
            return null;
        });
    }
}
