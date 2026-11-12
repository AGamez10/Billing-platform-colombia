package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.ApiKeyRecord;
import co.billingplatform.identity.application.IdentityPorts.ApiKeys;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claves de API para integraciones (p. ej. AdoptaFácil) con formato
 * {@code bpk_<prefijo 12 hex>_<secreto 43 base64url>}. El prefijo permite buscar la clave
 * sin tenant en contexto; el secreto solo se guarda como SHA-256 y se muestra una vez.
 */
public final class ApiKeyService {

    private static final Pattern FORMAT = Pattern.compile("^bpk_([0-9a-f]{12})_([A-Za-z0-9_-]{43})$");

    public record IssuedKey(UUID id, String prefix, String plaintext) {
        @Override
        public String toString() {
            return "IssuedKey[id=%s, prefix=%s]".formatted(id, prefix);
        }
    }

    public record ApiKeyPrincipal(TenantId tenantId, UUID keyId, Set<Permission> scopes) {
        public boolean can(Permission permission) {
            return scopes.contains(permission);
        }
    }

    private final ApiKeys keys;
    private final AuditTrail audit;
    private final Clock clock;

    public ApiKeyService(ApiKeys keys, AuditTrail audit, Clock clock) {
        this.keys = Objects.requireNonNull(keys);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public IssuedKey issue(TenantId tenantId, Actor actor, String name, Set<Permission> scopes, Instant expiresAt) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new DomainException("INVALID_API_KEY_NAME", "API key name is required (max 100 characters)");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new DomainException("INVALID_API_KEY_SCOPES", "At least one scope is required");
        }
        if (scopes.stream().anyMatch(Permission::isAdministrative)) {
            throw new DomainException("INVALID_API_KEY_SCOPES", "API keys cannot hold administrative permissions");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new DomainException("INVALID_API_KEY_EXPIRY", "Expiry must be in the future");
        }
        String prefix = OpaqueTokens.randomHex(6);
        String plaintext = "bpk_" + prefix + "_" + OpaqueTokens.randomBase64Url(32);
        UUID id = UUID.randomUUID();
        keys.insert(new ApiKeyRecord(tenantId, id, name.strip(), prefix, OpaqueTokens.sha256(plaintext),
                Set.copyOf(EnumSet.copyOf(scopes)), expiresAt));
        audit.record(new AuditTrail.Event(tenantId, actor, "API_KEY_ISSUED", "api_key", id.toString(),
                Map.of("prefix", prefix, "scopes", scopes.toString())));
        return new IssuedKey(id, prefix, plaintext);
    }

    public ApiKeyPrincipal authenticate(String presented) {
        if (presented == null) {
            throw invalid();
        }
        Matcher m = FORMAT.matcher(presented);
        if (!m.matches()) {
            throw invalid();
        }
        ApiKeyRecord record = keys.findByPrefix(m.group(1)).orElseThrow(ApiKeyService::invalid);
        if (!OpaqueTokens.matches(record.keyHash(), presented)) {
            throw invalid();
        }
        if (record.expiresAt() != null && !record.expiresAt().isAfter(clock.instant())) {
            throw invalid();
        }
        return new ApiKeyPrincipal(record.tenantId(), record.id(), record.scopes());
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_API_KEY", "Invalid API key");
    }
}
