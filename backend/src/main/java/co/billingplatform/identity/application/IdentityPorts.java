package co.billingplatform.identity.application;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Puertos de salida del módulo de identidad (implementados por adaptadores de persistencia/criptografía). */
public final class IdentityPorts {

    private IdentityPorts() {}

    public record UserAccount(UUID id, String email, String passwordHash, boolean active,
                              int failedLoginCount, Instant lockedUntil) {}

    public interface UserAccounts {
        Optional<UserAccount> findByEmail(String normalizedEmail);
        void recordFailedLogin(UUID userId, int failedLoginCount, Instant lockedUntil);
        void recordSuccessfulLogin(UUID userId);
    }

    /** @param organizationName nombre para que el usuario elija organización; puede faltar en pruebas */
    public record Membership(TenantId tenantId, Role role, String organizationName) {
        public Membership(TenantId tenantId, Role role) {
            this(tenantId, role, null);
        }
    }

    public interface Memberships {
        /** Membresías activas de organizaciones activas. */
        List<Membership> activeFor(UUID userId);
    }

    /** Argon2id en producción. */
    public interface PasswordHasher {
        String hash(String rawPassword);
        boolean matches(String rawPassword, String encodedHash);
        /** Hash válido de una contraseña aleatoria, para igualar tiempos cuando el usuario no existe. */
        String dummyHash();
    }

    public record RefreshTokenRecord(UUID id, UUID userId, TenantId tenantId, UUID familyId, byte[] tokenHash,
                                     Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        public boolean isSpent() {
            return revokedAt != null || replacedBy != null;
        }
    }

    public interface RefreshTokens {
        Optional<RefreshTokenRecord> findByHash(byte[] tokenHash);
        void insert(RefreshTokenRecord record);
        /**
         * Marca el token como reemplazado SOLO si aún no estaba gastado (compare-and-set).
         * @return {@code false} si otra petición lo gastó primero (uso concurrente = reúso)
         */
        boolean markReplaced(UUID tokenId, UUID replacedBy, Instant at);
        void revokeFamily(UUID familyId, Instant at);
    }

    public record ApiKeyRecord(TenantId tenantId, UUID id, String name, String prefix, byte[] keyHash,
                               Set<Permission> scopes, Instant expiresAt) {}

    /** Alta atómica de organización + usuario dueño + membresía ({@code register_organization}). */
    public interface OrganizationOnboarding {
        /**
         * @throws co.billingplatform.shared.domain.DomainException {@code EMAIL_ALREADY_REGISTERED}
         *         u {@code ORGANIZATION_ALREADY_REGISTERED}
         */
        void register(UUID organizationId, String legalName, co.billingplatform.shared.domain.PartyIdentification identification,
                      String organizationEmail, UUID ownerUserId, String ownerEmail, String passwordHash, String ownerFullName);
    }

    /** Lo implementa {@code subscriptions}: toda organización nueva arranca con la prueba gratis. */
    public interface TrialProvisioning {
        void startTrial(TenantId tenantId);
    }

    public interface ApiKeys {
        /** Solo claves no revocadas de organizaciones activas ({@code auth_find_api_key}). */
        Optional<ApiKeyRecord> findByPrefix(String prefix);
        void insert(ApiKeyRecord record);
    }
}
