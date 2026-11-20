package co.billingplatform.identity.application;

import co.billingplatform.shared.domain.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puertos de la verificación en dos pasos (V21). Datos de la cuenta del usuario, no de una organización. */
public final class TwoFactorPorts {

    private TwoFactorPorts() {}

    /** @param enabledAt null mientras la configuración está pendiente de confirmar con un código */
    public record State(UUID userId, byte[] sealedSecret, Instant enabledAt, long lastUsedStep, int failedCount,
                        Instant lockedUntil) {
        public boolean enabled() {
            return enabledAt != null;
        }
    }

    public interface Store {
        /** Bloquea la fila hasta el final de la transacción: dos verificaciones simultáneas no usan el mismo código. */
        Optional<State> lock(UUID userId);

        boolean isEnabled(UUID userId);

        /** Crea o reemplaza una configuración pendiente (nunca una activa). */
        void savePending(UUID userId, byte[] sealedSecret, Instant at);

        void enable(UUID userId, long step, Instant at);

        /** Código aceptado: guarda el paso usado y reinicia los fallos. */
        void recordSuccess(UUID userId, long lastUsedStep);

        void recordFailure(UUID userId, int failedCount, Instant lockedUntil);

        /** Borra la configuración y los códigos de recuperación. */
        void delete(UUID userId);

        void replaceRecoveryCodes(UUID userId, List<byte[]> hashes, Instant at);

        /** Marca el código como usado; false si no existe o ya se usó. */
        boolean consumeRecoveryCode(UUID userId, byte[] hash, Instant at);

        int recoveryCodesLeft(UUID userId);
    }

    public record Challenge(UUID userId, TenantId tenantId, int attempts, Instant expiresAt) {}

    /** Paso intermedio del inicio de sesión: la contraseña ya se verificó y falta el código. */
    public interface Challenges {
        void insert(byte[] tokenHash, UUID userId, TenantId tenantId, Instant expiresAt, Instant createdAt);

        Optional<Challenge> lock(byte[] tokenHash);

        void recordAttempt(byte[] tokenHash);

        void delete(byte[] tokenHash);

        void deleteExpired(Instant now);
    }

    /** Cifra el secreto TOTP ligado al usuario: copiado a otra fila no se descifra. */
    public interface SecretSealer {
        byte[] seal(UUID userId, byte[] secret);

        byte[] open(UUID userId, byte[] sealed);
    }
}
