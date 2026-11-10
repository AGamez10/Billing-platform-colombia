package co.billingplatform.identity.application;

import co.billingplatform.identity.domain.OpaqueTokens;
import co.billingplatform.identity.domain.PasswordPolicy;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.DomainException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Recuperar contraseña con un enlace de un solo uso (OWASP Forgot Password Cheat Sheet):
 * respuesta idéntica exista o no la cuenta, token de 256 bits guardado como hash, 30 minutos
 * de vigencia, solo vale el último enlace, máximo 5 solicitudes por hora por cuenta, y al
 * cambiarla se cierran todas las sesiones y se avisa al correo.
 */
public final class PasswordRecoveryService {

    public static final Duration VALIDITY = Duration.ofMinutes(30);
    static final int MAX_REQUESTS_PER_HOUR = 5;

    private final IdentityPorts.UserAccounts users;
    private final AccountPorts.PasswordResets resets;
    private final AccountPorts.Accounts accounts;
    private final IdentityPorts.PasswordHasher hasher;
    private final Mailer mailer;
    private final IdentityEmails emails;
    private final Clock clock;

    public PasswordRecoveryService(IdentityPorts.UserAccounts users, AccountPorts.PasswordResets resets,
                                   AccountPorts.Accounts accounts, IdentityPorts.PasswordHasher hasher, Mailer mailer,
                                   IdentityEmails emails, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.resets = Objects.requireNonNull(resets);
        this.accounts = Objects.requireNonNull(accounts);
        this.hasher = Objects.requireNonNull(hasher);
        this.mailer = Objects.requireNonNull(mailer);
        this.emails = Objects.requireNonNull(emails);
        this.clock = Objects.requireNonNull(clock);
    }

    /** No revela si la cuenta existe: siempre termina igual, sin errores de negocio. */
    public void request(String email) {
        if (email == null || email.length() > 254) {
            return;
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        users.findByEmail(normalized).filter(IdentityPorts.UserAccount::active).ifPresent(user -> {
            Instant now = clock.instant();
            if (resets.countSince(user.id(), now.minus(Duration.ofHours(1))) >= MAX_REQUESTS_PER_HOUR) {
                return;
            }
            resets.invalidateOpen(user.id(), now);
            String token = OpaqueTokens.randomBase64Url(32);
            resets.insert(UUID.randomUUID(), user.id(), OpaqueTokens.sha256(token), now.plus(VALIDITY), now);
            mailer.send(emails.passwordReset(user.email(), token, (int) VALIDITY.toMinutes()));
        });
    }

    public void confirm(String token, String newPassword) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,64}")) {
            throw invalidLink();
        }
        Instant now = clock.instant();
        AccountPorts.ResetToken reset = resets.lockByHash(OpaqueTokens.sha256(token)).orElseThrow(PasswordRecoveryService::invalidLink);
        if (reset.usedAt() != null || !now.isBefore(reset.expiresAt())) {
            throw invalidLink();
        }
        String email = accounts.activeEmail(reset.userId()).orElseThrow(PasswordRecoveryService::invalidLink);
        PasswordPolicy.validate(newPassword, email);
        accounts.changePassword(reset.userId(), hasher.hash(newPassword));
        resets.markUsed(reset.id(), now);
        resets.invalidateOpen(reset.userId(), now);
        accounts.revokeAllSessions(reset.userId(), now);
        mailer.send(emails.passwordChanged(email));
    }

    private static DomainException invalidLink() {
        return new DomainException("INVALID_RESET_LINK", "This link is invalid or expired. Request a new one.");
    }
}
