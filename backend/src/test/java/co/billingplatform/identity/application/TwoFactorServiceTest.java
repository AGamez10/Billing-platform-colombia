package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.UserAccount;
import co.billingplatform.identity.application.TwoFactorPorts.Challenge;
import co.billingplatform.identity.application.TwoFactorPorts.State;
import co.billingplatform.identity.domain.Base32;
import co.billingplatform.identity.domain.Totp;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verificación en dos pasos con dobles en memoria y reloj controlado. */
class TwoFactorServiceTest {

    private Instant now = Instant.parse("2026-11-19T15:00:10Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private final UUID user = UUID.randomUUID();
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final List<Mailer.Email> outbox = new ArrayList<>();

    private State state;
    private final Map<String, Instant> recovery = new HashMap<>(); // hash hex -> usado
    private final Map<String, Challenge> challenges = new HashMap<>();

    private final TwoFactorPorts.Store store = new TwoFactorPorts.Store() {
        @Override public Optional<State> lock(UUID u) { return Optional.ofNullable(state); }
        @Override public boolean isEnabled(UUID u) { return state != null && state.enabled(); }
        @Override public void savePending(UUID u, byte[] s, Instant at) {
            if (state == null || !state.enabled()) state = new State(u, s, null, 0, 0, null);
        }
        @Override public void enable(UUID u, long step, Instant at) {
            state = new State(u, state.sealedSecret(), at, step, 0, null);
        }
        @Override public void recordSuccess(UUID u, long step) {
            state = new State(u, state.sealedSecret(), state.enabledAt(), Math.max(step, state.lastUsedStep()), 0, null);
        }
        @Override public void recordFailure(UUID u, int c, Instant l) {
            state = new State(u, state.sealedSecret(), state.enabledAt(), state.lastUsedStep(), c, l);
        }
        @Override public void delete(UUID u) { state = null; recovery.clear(); }
        @Override public void replaceRecoveryCodes(UUID u, List<byte[]> hashes, Instant at) {
            recovery.clear();
            hashes.forEach(h -> recovery.put(Arrays.toString(h), null));
        }
        @Override public boolean consumeRecoveryCode(UUID u, byte[] h, Instant at) {
            String k = Arrays.toString(h);
            if (recovery.containsKey(k) && recovery.get(k) == null) {
                recovery.put(k, at);
                return true;
            }
            return false;
        }
        @Override public int recoveryCodesLeft(UUID u) { return (int) recovery.values().stream().filter(v -> v == null).count(); }
    };

    private final TwoFactorPorts.Challenges challengeStore = new TwoFactorPorts.Challenges() {
        @Override public void insert(byte[] h, UUID u, TenantId t, Instant exp, Instant at) {
            challenges.put(Arrays.toString(h), new Challenge(u, t, 0, exp));
        }
        @Override public Optional<Challenge> lock(byte[] h) { return Optional.ofNullable(challenges.get(Arrays.toString(h))); }
        @Override public void recordAttempt(byte[] h) {
            Challenge c = challenges.get(Arrays.toString(h));
            challenges.put(Arrays.toString(h), new Challenge(c.userId(), c.tenantId(), c.attempts() + 1, c.expiresAt()));
        }
        @Override public void delete(byte[] h) { challenges.remove(Arrays.toString(h)); }
        @Override public void deleteExpired(Instant n) { challenges.values().removeIf(c -> !c.expiresAt().isAfter(n)); }
    };

    /** Sellado de juguete que exige el mismo usuario al abrir. */
    private final TwoFactorPorts.SecretSealer sealer = new TwoFactorPorts.SecretSealer() {
        @Override public byte[] seal(UUID u, byte[] s) {
            byte[] id = u.toString().getBytes();
            byte[] out = Arrays.copyOf(id, id.length + s.length);
            System.arraycopy(s, 0, out, id.length, s.length);
            return out;
        }
        @Override public byte[] open(UUID u, byte[] sealed) {
            byte[] id = u.toString().getBytes();
            assertTrue(Arrays.equals(id, Arrays.copyOf(sealed, id.length)), "sellado de otro usuario");
            return Arrays.copyOfRange(sealed, id.length, sealed.length);
        }
    };

    private final AccountPorts.Accounts accounts = new AccountPorts.Accounts() {
        @Override public Optional<String> activeEmail(UUID id) { return Optional.of("ana@example.com"); }
        @Override public void changePassword(UUID id, String h) {}
        @Override public void revokeAllSessions(UUID id, Instant at) {}
        @Override public void create(UUID id, String email, String h, String name) {}
        @Override public Optional<String> fullName(UUID id) { return Optional.of("Ana"); }
    };

    private final IdentityPorts.UserAccounts users = new IdentityPorts.UserAccounts() {
        @Override public Optional<UserAccount> findByEmail(String e) {
            return Optional.of(new UserAccount(user, e, "h:clave correcta", true, 0, null));
        }
        @Override public void recordFailedLogin(UUID id, int c, Instant l) {}
        @Override public void recordSuccessfulLogin(UUID id) {}
    };

    private final IdentityPorts.PasswordHasher hasher = new IdentityPorts.PasswordHasher() {
        @Override public String hash(String raw) { return "h:" + raw; }
        @Override public boolean matches(String raw, String encoded) { return encoded.equals("h:" + raw); }
        @Override public String dummyHash() { return "h:x"; }
    };

    private final TwoFactorService service = new TwoFactorService(store, challengeStore, sealer, accounts, users, hasher,
            outbox::add, new IdentityEmails("Billing Platform", "https://app.example.co"), "Billing Platform", clock);

    private byte[] secret;

    private List<String> enabled() {
        TwoFactorService.Setup setup = service.beginSetup(user);
        secret = Base32.decode(setup.secret());
        assertTrue(setup.otpauthUri().startsWith("otpauth://totp/Billing%20Platform:ana%40example.com?secret=" + setup.secret()));
        return service.enable(user, code(0));
    }

    private String code(int stepsFromNow) {
        return Totp.code(secret, Totp.stepAt(now) + stepsFromNow);
    }

    private static String errorCode(Runnable r) {
        return assertThrows(DomainException.class, r::run).code();
    }

    @Test
    @DisplayName("activar exige un código de la app; entrega 10 códigos de recuperación y avisa por correo")
    void enable() {
        TwoFactorService.Setup setup = service.beginSetup(user);
        secret = Base32.decode(setup.secret());
        assertEquals(20, secret.length);
        assertFalse(service.isEnabled(user), "pendiente hasta confirmar");
        assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.enable(user, "000000".equals(code(0)) ? "111111" : "000000")));
        List<String> codes = service.enable(user, code(0));
        assertEquals(10, codes.size());
        assertTrue(service.isEnabled(user));
        assertEquals(new TwoFactorService.Status(true, 10), service.status(user));
        assertEquals("TWO_FACTOR_ALREADY_ENABLED", errorCode(() -> service.beginSetup(user)));
        assertEquals(1, outbox.size());
        assertTrue(outbox.getFirst().subject().contains("activada"));
    }

    @Test
    @DisplayName("activar sin haber empezado la configuración se rechaza")
    void enableWithoutSetup() {
        assertEquals("TWO_FACTOR_SETUP_REQUIRED", errorCode(() -> service.enable(user, "123456")));
    }

    @Test
    @DisplayName("inicio de sesión: el reto se completa con el código y sirve una sola vez")
    void challengeFlow() {
        enabled();
        String token = service.challenge(user, tenant);
        assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.completeChallenge(token, code(0))),
                "el código con el que se activó ya se usó (antirreplay)");
        TwoFactorService.Verified v = service.completeChallenge(token, code(1));
        assertEquals(user, v.userId());
        assertEquals(tenant, v.tenantId());
        assertEquals("TWO_FACTOR_CHALLENGE_EXPIRED", errorCode(() -> service.completeChallenge(token, code(1))));
    }

    @Test
    @DisplayName("un reto vence a los 5 minutos y se agota a los 5 intentos")
    void challengeLimits() {
        enabled();
        String token = service.challenge(user, tenant);
        now = now.plus(TwoFactorService.CHALLENGE_TTL);
        assertEquals("TWO_FACTOR_CHALLENGE_EXPIRED", errorCode(() -> service.completeChallenge(token, "123456")));

        now = now.plus(Duration.ofHours(1));
        String second = service.challenge(user, tenant);
        for (int i = 0; i < TwoFactorService.CHALLENGE_ATTEMPTS; i++) {
            assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.completeChallenge(second, "999999".equals(code(0)) ? "888888" : "999999")));
        }
        assertEquals("TWO_FACTOR_CHALLENGE_EXPIRED", errorCode(() -> service.completeChallenge(second, code(0))),
                "agotado: ni el código correcto lo abre");
        assertEquals("TWO_FACTOR_CHALLENGE_EXPIRED", errorCode(() -> service.completeChallenge("nope", code(0))));
        assertEquals("TWO_FACTOR_CHALLENGE_EXPIRED", errorCode(() -> service.completeChallenge(null, code(0))));
    }

    @Test
    @DisplayName("los fallos de código bloquean al usuario aunque pida retos nuevos")
    void lockoutAcrossChallenges() {
        enabled();
        String wrong = "424242".equals(code(1)) ? "434343" : "424242";
        for (int i = 0; i < 5; i++) {
            String token = service.challenge(user, tenant);
            assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.completeChallenge(token, wrong)));
        }
        String token = service.challenge(user, tenant);
        assertEquals("TWO_FACTOR_LOCKED", errorCode(() -> service.completeChallenge(token, code(1))),
                "tras 5 fallos queda bloqueado 15 minutos aunque acierte");
        now = now.plus(Duration.ofMinutes(16));
        String later = service.challenge(user, tenant);
        assertEquals(user, service.completeChallenge(later, code(0)).userId());
        assertEquals(0, state.failedCount(), "acertar reinicia los fallos");
    }

    @Test
    @DisplayName("un código de recuperación entra una sola vez")
    void recoveryCode() {
        List<String> codes = enabled();
        String token = service.challenge(user, tenant);
        assertEquals(user, service.completeChallenge(token, codes.get(3).toLowerCase()).userId());
        assertEquals(9, service.status(user).recoveryCodesLeft());
        String again = service.challenge(user, tenant);
        assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.completeChallenge(again, codes.get(3))));
    }

    @Test
    @DisplayName("regenerar invalida los códigos anteriores")
    void regenerate() {
        List<String> old = enabled();
        List<String> fresh = service.regenerateRecoveryCodes(user, code(1));
        assertEquals(10, fresh.size());
        String token = service.challenge(user, tenant);
        assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.completeChallenge(token, old.getFirst())));
        assertEquals(user, service.completeChallenge(token, fresh.getFirst()).userId());
    }

    @Test
    @DisplayName("desactivar pide contraseña y código, borra todo y avisa por correo")
    void disable() {
        List<String> codes = enabled();
        assertEquals("INVALID_PASSWORD", errorCode(() -> service.disable(user, "otra", code(1))));
        assertEquals("INVALID_TWO_FACTOR_CODE", errorCode(() -> service.disable(user, "clave correcta", "ZZZZZ-ZZZZZ")));
        service.disable(user, "clave correcta", codes.get(0));
        assertFalse(service.isEnabled(user));
        assertEquals(new TwoFactorService.Status(false, 0), service.status(user));
        assertTrue(outbox.getLast().subject().contains("desactivada"));
        assertEquals("TWO_FACTOR_NOT_ENABLED", errorCode(() -> service.regenerateRecoveryCodes(user, code(1))));
    }

    @Test
    @DisplayName("los secretos y códigos no aparecen al imprimir")
    void noSecretsInToString() {
        TwoFactorService.Setup setup = service.beginSetup(user);
        assertFalse(setup.toString().contains(setup.secret()));
    }
}
