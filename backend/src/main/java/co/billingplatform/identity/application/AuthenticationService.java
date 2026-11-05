package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.Membership;
import co.billingplatform.identity.application.IdentityPorts.Memberships;
import co.billingplatform.identity.application.IdentityPorts.PasswordHasher;
import co.billingplatform.identity.application.IdentityPorts.UserAccount;
import co.billingplatform.identity.application.IdentityPorts.UserAccounts;
import co.billingplatform.identity.domain.LoginThrottlePolicy;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Inicio de sesión con correo y contraseña.
 *
 * <p>Contra enumeración de usuarios: usuario inexistente, contraseña errada, cuenta
 * deshabilitada o bloqueada responden el mismo error, y siempre se ejecuta una verificación
 * Argon2 (contra un hash ficticio si no hay usuario) para que el tiempo de respuesta no
 * delate si el correo existe.
 */
public final class AuthenticationService {

    public record AuthenticatedUser(UUID userId, List<Membership> memberships) {}

    private final UserAccounts users;
    private final Memberships memberships;
    private final PasswordHasher hasher;
    private final Clock clock;

    public AuthenticationService(UserAccounts users, Memberships memberships, PasswordHasher hasher, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.memberships = Objects.requireNonNull(memberships);
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
    }

    public AuthenticatedUser authenticate(String email, String password) {
        if (email == null || password == null || password.length() > 1024) {
            hasher.matches("x", hasher.dummyHash());
            throw invalid();
        }
        Instant now = clock.instant();
        Optional<UserAccount> found = users.findByEmail(email.strip().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            hasher.matches(password, hasher.dummyHash());
            throw invalid();
        }
        UserAccount user = found.get();
        boolean locked = user.lockedUntil() != null && user.lockedUntil().isAfter(now);
        boolean passwordOk = hasher.matches(password, user.passwordHash());

        if (locked || !user.active()) {
            throw invalid();
        }
        if (!passwordOk) {
            int failures = user.failedLoginCount() + 1;
            users.recordFailedLogin(user.id(), failures, LoginThrottlePolicy.lockUntil(failures, now).orElse(null));
            throw invalid();
        }
        users.recordSuccessfulLogin(user.id());
        return new AuthenticatedUser(user.id(), memberships.activeFor(user.id()));
    }

    /** El tenant de la sesión debe ser una membresía activa del usuario; nunca se acepta otro. */
    public Membership selectTenant(AuthenticatedUser user, TenantId requested) {
        return user.memberships().stream()
                .filter(m -> m.tenantId().equals(requested))
                .findFirst()
                .orElseThrow(() -> new DomainException("TENANT_ACCESS_DENIED", "No access to that organization"));
    }

    private static DomainException invalid() {
        return new DomainException("INVALID_CREDENTIALS", "Invalid email or password");
    }
}
