package co.billingplatform.identity.application;

import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puertos de recuperación de cuenta y gestión del equipo (V14). */
public final class AccountPorts {

    private AccountPorts() {}

    public record ResetToken(UUID id, UUID userId, Instant expiresAt, Instant usedAt) {}

    public interface PasswordResets {
        /** Inutiliza los enlaces abiertos del usuario: solo sirve el último. */
        void invalidateOpen(UUID userId, Instant at);

        void insert(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt, Instant createdAt);

        /** Bloquea la fila hasta el final de la transacción (dos usos simultáneos no pasan). */
        Optional<ResetToken> lockByHash(byte[] tokenHash);

        void markUsed(UUID id, Instant at);

        int countSince(UUID userId, Instant since);
    }

    public interface Accounts {
        /** Correo de un usuario activo. */
        Optional<String> activeEmail(UUID userId);

        /** Cambia la contraseña y levanta el bloqueo por intentos fallidos. */
        void changePassword(UUID userId, String passwordHash);

        /** Cierra todas las sesiones del usuario (refresh tokens). */
        void revokeAllSessions(UUID userId, Instant at);

        /** @throws co.billingplatform.shared.domain.DomainException {@code EMAIL_ALREADY_REGISTERED} */
        void create(UUID userId, String email, String passwordHash, String fullName);

        Optional<String> fullName(UUID userId);
    }

    public record Member(UUID userId, String email, String fullName, Role role, boolean active, Instant since) {}

    /** Membresías de la organización en contexto (RLS). */
    public interface Team {
        List<Member> members(TenantId tenantId);

        Optional<Member> member(TenantId tenantId, UUID userId);

        long activeCount(TenantId tenantId);

        /** Crea o reactiva la membresía con ese rol. */
        void activate(TenantId tenantId, UUID userId, Role role);

        void changeRole(TenantId tenantId, UUID userId, Role role);

        void revoke(TenantId tenantId, UUID userId);
    }

    public record Invitation(TenantId tenantId, UUID id, String email, Role role, String organizationName,
                             Instant expiresAt, Instant acceptedAt, Instant revokedAt, Instant createdAt) {
        public boolean isOpen(Instant now) {
            return acceptedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }

    public interface Invitations {
        void revokeOpenFor(TenantId tenantId, String email, Instant at);

        void insert(TenantId tenantId, UUID id, String email, Role role, byte[] tokenHash, UUID invitedBy,
                    Instant expiresAt, Instant createdAt);

        /** Sin tenant en contexto ({@code invitation_find}). */
        Optional<Invitation> findByHash(byte[] tokenHash);

        List<Invitation> open(TenantId tenantId, Instant now);

        long openCount(TenantId tenantId, Instant now);

        boolean revoke(TenantId tenantId, UUID id, Instant at);

        /** Compare-and-set: solo si sigue abierta. */
        boolean markAccepted(TenantId tenantId, UUID id, UUID userId, Instant at);
    }

    /** Lo implementa {@code subscriptions}: usuarios máximos del plan ({@code empty} = sin límite). */
    public interface SeatLimit {
        Optional<Integer> maxUsers(TenantId tenantId);
    }
}
