package co.billingplatform.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Bloqueo progresivo por cuenta ante fallos consecutivos: a partir de 5 fallos, 15 minutos,
 * duplicándose con cada fallo adicional hasta 24 horas. Complementa (no reemplaza) el rate
 * limiting por IP en la capa web.
 */
public final class LoginThrottlePolicy {

    public static final int FREE_ATTEMPTS = 5;
    static final Duration FIRST_LOCK = Duration.ofMinutes(15);
    static final Duration MAX_LOCK = Duration.ofHours(24);

    private LoginThrottlePolicy() {}

    /** @param consecutiveFailures fallos consecutivos incluyendo el que acaba de ocurrir */
    public static Optional<Instant> lockUntil(int consecutiveFailures, Instant now) {
        if (consecutiveFailures < FREE_ATTEMPTS) {
            return Optional.empty();
        }
        int doublings = Math.min(consecutiveFailures - FREE_ATTEMPTS, 10);
        Duration lock = FIRST_LOCK.multipliedBy(1L << doublings);
        if (lock.compareTo(MAX_LOCK) > 0) {
            lock = MAX_LOCK;
        }
        return Optional.of(now.plus(lock));
    }
}
