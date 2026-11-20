package co.billingplatform.identity.application;

import co.billingplatform.identity.application.TwoFactorPorts.Challenge;
import co.billingplatform.identity.application.TwoFactorPorts.State;
import co.billingplatform.identity.domain.Base32;
import co.billingplatform.identity.domain.LoginThrottlePolicy;
import co.billingplatform.identity.domain.RecoveryCodes;
import co.billingplatform.identity.domain.Totp;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.shared.domain.TenantId;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Verificación en dos pasos con app de autenticación (TOTP) y códigos de recuperación (ADR-0034).
 *
 * <ul>
 *   <li>Activar exige escanear el QR y confirmar con un código: no queda activa una configuración
 *       que el usuario no puede usar.</li>
 *   <li>Al iniciar sesión, tras la contraseña se entrega un reto de un solo uso (256 bits, 5
 *       minutos, 5 intentos) y la sesión solo se abre con el código.</li>
 *   <li>Los fallos de código se cuentan por usuario y bloquean de forma progresiva (la misma
 *       política que la contraseña), sin reiniciarse al acertar la contraseña: conocer la clave no
 *       da intentos ilimitados contra el código.</li>
 *   <li>Desactivar pide contraseña y código; activar o desactivar avisa por correo.</li>
 * </ul>
 */
public final class TwoFactorService {

    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    public static final int CHALLENGE_ATTEMPTS = 5;

    public record Status(boolean enabled, int recoveryCodesLeft) {}

    /** El secreto se muestra solo durante la configuración (QR y texto para escribirlo a mano). */
    public record Setup(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "Setup[***]";
        }
    }

    public record Verified(UUID userId, TenantId tenantId) {}

    private final TwoFactorPorts.Store store;
    private final TwoFactorPorts.Challenges challenges;
    private final TwoFactorPorts.SecretSealer sealer;
    private final AccountPorts.Accounts accounts;
    private final IdentityPorts.UserAccounts users;
    private final IdentityPorts.PasswordHasher hasher;
    private final Mailer mailer;
    private final IdentityEmails emails;
    private final String issuer;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(TwoFactorPorts.Store store, TwoFactorPorts.Challenges challenges,
                            TwoFactorPorts.SecretSealer sealer, AccountPorts.Accounts accounts,
                            IdentityPorts.UserAccounts users, IdentityPorts.PasswordHasher hasher, Mailer mailer,
                            IdentityEmails emails, String issuer, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.challenges = Objects.requireNonNull(challenges);
        this.sealer = Objects.requireNonNull(sealer);
        this.accounts = Objects.requireNonNull(accounts);
        this.users = Objects.requireNonNull(users);
        this.hasher = Objects.requireNonNull(hasher);
        this.mailer = Objects.requireNonNull(mailer);
        this.emails = Objects.requireNonNull(emails);
        this.issuer = Objects.requireNonNull(issuer);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean isEnabled(UUID userId) {
        return store.isEnabled(userId);
    }

    public Status status(UUID userId) {
        boolean enabled = store.isEnabled(userId);
        return new Status(enabled, enabled ? store.recoveryCodesLeft(userId) : 0);
    }

    public Setup beginSetup(UUID userId) {
        if (store.lock(userId).map(State::enabled).orElse(false)) {
            throw new DomainException("TWO_FACTOR_ALREADY_ENABLED", "Two-step verification is already on");
        }
        String email = accounts.activeEmail(userId)
                .orElseThrow(() -> new DomainException("USER_NOT_FOUND", "User not found"));
        byte[] secret = new byte[Totp.SECRET_BYTES];
        random.nextBytes(secret);
        store.savePending(userId, sealer.seal(userId, secret), clock.instant());
        String base32 = Base32.encode(secret);
        return new Setup(base32, Totp.provisioningUri(issuer, email, base32));
    }

    /** @return los códigos de recuperación, que se muestran una sola vez */
    public List<String> enable(UUID userId, String code) {
        State state = store.lock(userId)
                .orElseThrow(() -> new DomainException("TWO_FACTOR_SETUP_REQUIRED", "Start the setup first"));
        if (state.enabled()) {
            throw new DomainException("TWO_FACTOR_ALREADY_ENABLED", "Two-step verification is already on");
        }
        Instant now = clock.instant();
        OptionalLong step = Totp.verify(sealer.open(userId, state.sealedSecret()), code, now, state.lastUsedStep());
        if (step.isEmpty()) {
            throw invalidCode();
        }
        store.enable(userId, step.getAsLong(), now);
        List<String> codes = newRecoveryCodes(userId, now);
        accounts.activeEmail(userId).ifPresent(to -> mailer.send(emails.twoFactorChanged(to, true)));
        return codes;
    }

    public void disable(UUID userId, String password, String code) {
        String email = accounts.activeEmail(userId)
                .orElseThrow(() -> new DomainException("USER_NOT_FOUND", "User not found"));
        boolean passwordOk = users.findByEmail(email)
                .map(u -> password != null && password.length() <= 1024 && hasher.matches(password, u.passwordHash()))
                .orElse(false);
        if (!passwordOk) {
            throw new DomainException("INVALID_PASSWORD", "The password is not correct");
        }
        verifySecondFactor(userId, code);
        store.delete(userId);
        mailer.send(emails.twoFactorChanged(email, false));
    }

    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        verifySecondFactor(userId, code);
        return newRecoveryCodes(userId, clock.instant());
    }

    /** Tras verificar la contraseña de un usuario con dos pasos activos. @return el reto (secreto de un solo uso) */
    public String challenge(UUID userId, TenantId tenantId) {
        Instant now = clock.instant();
        challenges.deleteExpired(now);
        String token = OpaqueTokens.randomBase64Url(32);
        challenges.insert(OpaqueTokens.sha256(token), userId, tenantId, now.plus(CHALLENGE_TTL), now);
        return token;
    }

    /**
     * Completa el inicio de sesión. Debe ejecutarse en una transacción que conserve las escrituras
     * aunque falle (intentos del reto y fallos del usuario).
     */
    public Verified completeChallenge(String token, String code) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw challengeExpired();
        }
        byte[] hash = OpaqueTokens.sha256(token);
        Challenge challenge = challenges.lock(hash).orElseThrow(TwoFactorService::challengeExpired);
        if (!challenge.expiresAt().isAfter(clock.instant()) || challenge.attempts() >= CHALLENGE_ATTEMPTS) {
            challenges.delete(hash);
            throw challengeExpired();
        }
        try {
            verifySecondFactor(challenge.userId(), code);
        } catch (DomainException e) {
            if (challenge.attempts() + 1 >= CHALLENGE_ATTEMPTS || !"INVALID_TWO_FACTOR_CODE".equals(e.code())) {
                challenges.delete(hash);
            } else {
                challenges.recordAttempt(hash);
            }
            throw e;
        }
        challenges.delete(hash);
        return new Verified(challenge.userId(), challenge.tenantId());
    }

    /** Código de la app (6 dígitos) o de recuperación ({@code XXXXX-XXXXX}). */
    private void verifySecondFactor(UUID userId, String code) {
        State state = store.lock(userId).filter(State::enabled)
                .orElseThrow(() -> new DomainException("TWO_FACTOR_NOT_ENABLED", "Two-step verification is off"));
        Instant now = clock.instant();
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new DomainException("TWO_FACTOR_LOCKED", "Too many wrong codes, try again later");
        }
        if (Totp.normalize(code) != null) {
            OptionalLong step = Totp.verify(sealer.open(userId, state.sealedSecret()), code, now, state.lastUsedStep());
            if (step.isPresent()) {
                store.recordSuccess(userId, step.getAsLong());
                return;
            }
        } else {
            String recovery = RecoveryCodes.normalize(code);
            if (recovery != null && store.consumeRecoveryCode(userId, recoveryHash(userId, recovery), now)) {
                store.recordSuccess(userId, state.lastUsedStep());
                return;
            }
        }
        int failures = state.failedCount() + 1;
        store.recordFailure(userId, failures, LoginThrottlePolicy.lockUntil(failures, now).orElse(null));
        throw invalidCode();
    }

    private List<String> newRecoveryCodes(UUID userId, Instant now) {
        List<String> codes = RecoveryCodes.generate();
        store.replaceRecoveryCodes(userId,
                codes.stream().map(c -> recoveryHash(userId, RecoveryCodes.normalize(c))).toList(), now);
        return codes;
    }

    /** El usuario va en el hash: el mismo código de dos personas no coincide. */
    static byte[] recoveryHash(UUID userId, String normalizedCode) {
        return OpaqueTokens.sha256(userId.toString().toLowerCase(Locale.ROOT) + ":" + normalizedCode);
    }

    private static DomainException invalidCode() {
        return new DomainException("INVALID_TWO_FACTOR_CODE", "The verification code is not valid");
    }

    private static DomainException challengeExpired() {
        return new DomainException("TWO_FACTOR_CHALLENGE_EXPIRED", "Sign in again");
    }
}
