package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.ApiKeyRecord;
import co.billingplatform.identity.application.IdentityPorts.Membership;
import co.billingplatform.identity.application.IdentityPorts.RefreshTokenRecord;
import co.billingplatform.identity.application.IdentityPorts.UserAccount;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IdentityServicesTest {

    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final UUID ana = UUID.randomUUID();
    private Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    // ---------------------------------------------------------------- dobles en memoria
    private final Map<String, UserAccount> usersByEmail = new HashMap<>();
    private final Map<UUID, List<Membership>> membershipsByUser = new HashMap<>();
    private int hasherCalls;

    private final IdentityPorts.PasswordHasher hasher = new IdentityPorts.PasswordHasher() {
        @Override public String hash(String raw) { return "h:" + raw; }
        @Override public boolean matches(String raw, String encoded) { hasherCalls++; return encoded.equals("h:" + raw); }
        @Override public String dummyHash() { return "h:" + UUID.randomUUID(); }
    };

    private final IdentityPorts.UserAccounts users = new IdentityPorts.UserAccounts() {
        @Override public Optional<UserAccount> findByEmail(String email) { return Optional.ofNullable(usersByEmail.get(email)); }
        @Override public void recordFailedLogin(UUID id, int count, Instant lockedUntil) { replace(id, count, lockedUntil); }
        @Override public void recordSuccessfulLogin(UUID id) { replace(id, 0, null); }
        private void replace(UUID id, int count, Instant lockedUntil) {
            usersByEmail.replaceAll((k, u) -> u.id().equals(id)
                    ? new UserAccount(u.id(), u.email(), u.passwordHash(), u.active(), count, lockedUntil) : u);
        }
    };

    private final IdentityPorts.Memberships memberships = userId -> membershipsByUser.getOrDefault(userId, List.of());

    private final Map<UUID, RefreshTokenRecord> refreshStore = new HashMap<>();
    private final IdentityPorts.RefreshTokens refreshTokens = new IdentityPorts.RefreshTokens() {
        @Override public Optional<RefreshTokenRecord> findByHash(byte[] h) {
            return refreshStore.values().stream().filter(r -> java.util.Arrays.equals(r.tokenHash(), h)).findFirst();
        }
        @Override public void insert(RefreshTokenRecord r) { refreshStore.put(r.id(), r); }
        @Override public boolean markReplaced(UUID id, UUID by, Instant at) {
            RefreshTokenRecord r = refreshStore.get(id);
            if (r == null || r.isSpent()) return false;
            refreshStore.put(id, new RefreshTokenRecord(r.id(), r.userId(), r.tenantId(), r.familyId(), r.tokenHash(), r.expiresAt(), null, by));
            return true;
        }
        @Override public void revokeFamily(UUID familyId, Instant at) {
            refreshStore.replaceAll((k, r) -> r.familyId().equals(familyId) && r.revokedAt() == null
                    ? new RefreshTokenRecord(r.id(), r.userId(), r.tenantId(), r.familyId(), r.tokenHash(), r.expiresAt(), at, r.replacedBy()) : r);
        }
    };

    private final Map<String, ApiKeyRecord> apiKeyStore = new HashMap<>();
    private final List<String> auditActions = new ArrayList<>();

    private void registerAna(boolean active) {
        usersByEmail.put("ana@fundacion.co", new UserAccount(ana, "ana@fundacion.co", "h:clave muy segura 2026", active, 0, null));
        membershipsByUser.put(ana, List.of(new Membership(tenantA, Role.ADMIN)));
    }

    private AuthenticationService auth() {
        return new AuthenticationService(users, memberships, hasher, clock);
    }

    private RefreshTokenService refresh() {
        return new RefreshTokenService(refreshTokens, memberships, clock);
    }

    private ApiKeyService apiKeys() {
        return new ApiKeyService(new IdentityPorts.ApiKeys() {
            @Override public Optional<ApiKeyRecord> findByPrefix(String p) { return Optional.ofNullable(apiKeyStore.get(p)); }
            @Override public void insert(ApiKeyRecord r) { apiKeyStore.put(r.prefix(), r); }
        }, e -> auditActions.add(e.action()), clock);
    }

    // ---------------------------------------------------------------- autenticación
    @Test
    @DisplayName("login correcto devuelve las membresías; el correo se normaliza")
    void loginSucceeds() {
        registerAna(true);
        var user = auth().authenticate("  ANA@Fundacion.co ", "clave muy segura 2026");
        assertEquals(ana, user.userId());
        assertEquals(Role.ADMIN, auth().selectTenant(user, tenantA).role());
        assertEquals("TENANT_ACCESS_DENIED", assertThrows(DomainException.class,
                () -> auth().selectTenant(user, tenantB)).code(), "no puede elegir una organización ajena");
    }

    @Test
    @DisplayName("anti-enumeración: mismo error y misma verificación Argon2 exista o no el usuario")
    void sameErrorForUnknownUser() {
        registerAna(true);
        DomainException unknown = assertThrows(DomainException.class, () -> auth().authenticate("nadie@x.co", "lo que sea 12345"));
        int callsAfterUnknown = hasherCalls;
        DomainException wrong = assertThrows(DomainException.class, () -> auth().authenticate("ana@fundacion.co", "equivocada 12345"));

        assertEquals("INVALID_CREDENTIALS", unknown.code());
        assertEquals(unknown.code(), wrong.code());
        assertEquals(unknown.getMessage(), wrong.getMessage());
        assertEquals(1, callsAfterUnknown, "se verificó contra un hash ficticio");
        assertEquals(2, hasherCalls);
    }

    @Test
    @DisplayName("5 fallos bloquean la cuenta; ni la contraseña correcta entra hasta que expire el bloqueo")
    void lockoutAfterFailures() {
        registerAna(true);
        for (int i = 0; i < 5; i++) {
            assertThrows(DomainException.class, () -> auth().authenticate("ana@fundacion.co", "equivocada 12345"));
        }
        assertEquals(T0.plus(Duration.ofMinutes(15)), usersByEmail.get("ana@fundacion.co").lockedUntil());
        assertEquals("INVALID_CREDENTIALS", assertThrows(DomainException.class,
                () -> auth().authenticate("ana@fundacion.co", "clave muy segura 2026")).code());

        clock = Clock.fixed(T0.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        assertDoesNotThrow(() -> auth().authenticate("ana@fundacion.co", "clave muy segura 2026"));
        assertEquals(0, usersByEmail.get("ana@fundacion.co").failedLoginCount(), "el éxito reinicia el contador");
    }

    @Test
    @DisplayName("una cuenta deshabilitada no entra aunque la contraseña sea correcta")
    void disabledAccount() {
        registerAna(false);
        assertThrows(DomainException.class, () -> auth().authenticate("ana@fundacion.co", "clave muy segura 2026"));
    }

    // ---------------------------------------------------------------- refresh tokens
    @Test
    @DisplayName("rotación: cada uso entrega un token nuevo; nunca se guarda el token en claro")
    void refreshRotation() {
        registerAna(true);
        var first = refresh().issue(ana, tenantA);
        var second = refresh().rotate(first.token());

        assertNotEquals(first.token(), second.token());
        assertEquals(Role.ADMIN, second.role());
        assertTrue(refreshStore.values().stream().noneMatch(r -> new String(r.tokenHash()).contains(first.token())));
        assertFalse(first.toString().contains(first.token()), "toString no expone el token");
    }

    @Test
    @DisplayName("reúso de un token rotado revoca toda la familia (token robado)")
    void reuseRevokesFamily() {
        registerAna(true);
        var first = refresh().issue(ana, tenantA);
        var second = refresh().rotate(first.token());

        assertEquals("REFRESH_TOKEN_REUSED", assertThrows(DomainException.class, () -> refresh().rotate(first.token())).code());
        assertEquals("REFRESH_TOKEN_REUSED", assertThrows(DomainException.class, () -> refresh().rotate(second.token())).code(),
                "el token legítimo más reciente también quedó revocado");
    }

    @Test
    @DisplayName("token expirado, desconocido o de un usuario retirado de la organización no renueva")
    void refreshRejections() {
        registerAna(true);
        var issued = refresh().issue(ana, tenantA);
        assertEquals("INVALID_REFRESH_TOKEN", assertThrows(DomainException.class, () -> refresh().rotate("inventado")).code());

        membershipsByUser.put(ana, List.of());
        assertEquals("INVALID_REFRESH_TOKEN", assertThrows(DomainException.class, () -> refresh().rotate(issued.token())).code());

        membershipsByUser.put(ana, List.of(new Membership(tenantA, Role.ADMIN)));
        var fresh = refresh().issue(ana, tenantA);
        clock = Clock.fixed(T0.plus(RefreshTokenService.TTL).plusSeconds(1), ZoneOffset.UTC);
        assertEquals("INVALID_REFRESH_TOKEN", assertThrows(DomainException.class, () -> refresh().rotate(fresh.token())).code());
    }

    @Test
    @DisplayName("cerrar sesión revoca la familia")
    void logoutRevokes() {
        registerAna(true);
        var issued = refresh().issue(ana, tenantA);
        refresh().revoke(issued.token());
        assertThrows(DomainException.class, () -> refresh().rotate(issued.token()));
    }

    // ---------------------------------------------------------------- API keys
    @Test
    @DisplayName("API key: se muestra una vez, se guarda como hash y autentica con su tenant y scopes")
    void apiKeyLifecycle() {
        var issued = apiKeys().issue(tenantA, Actor.user(ana), "AdoptaFácil", Set.of(Permission.INVOICE_WRITE, Permission.INVOICE_READ), null);

        assertTrue(issued.plaintext().matches("bpk_[0-9a-f]{12}_[A-Za-z0-9_-]{43}"));
        assertFalse(issued.toString().contains(issued.plaintext()));
        var principal = apiKeys().authenticate(issued.plaintext());
        assertEquals(tenantA, principal.tenantId());
        assertTrue(principal.can(Permission.INVOICE_WRITE));
        assertFalse(principal.can(Permission.INVOICE_FINALIZE));
        assertEquals(List.of("API_KEY_ISSUED"), auditActions);
    }

    @Test
    @DisplayName("API key alterada, mal formada o vencida no autentica")
    void apiKeyRejections() {
        var issued = apiKeys().issue(tenantA, Actor.user(ana), "ERP", Set.of(Permission.INVOICE_READ), T0.plus(Duration.ofDays(1)));
        String tampered = issued.plaintext().substring(0, issued.plaintext().length() - 1)
                + (issued.plaintext().endsWith("A") ? "B" : "A");

        assertEquals("INVALID_API_KEY", assertThrows(DomainException.class, () -> apiKeys().authenticate(tampered)).code());
        assertThrows(DomainException.class, () -> apiKeys().authenticate("Bearer algo"));
        assertThrows(DomainException.class, () -> apiKeys().authenticate(null));

        clock = Clock.fixed(T0.plus(Duration.ofDays(2)), ZoneOffset.UTC);
        assertThrows(DomainException.class, () -> apiKeys().authenticate(issued.plaintext()));
    }

    @Test
    @DisplayName("una API key nunca recibe permisos administrativos")
    void apiKeyCannotBeAdmin() {
        assertEquals("INVALID_API_KEY_SCOPES", assertThrows(DomainException.class,
                () -> apiKeys().issue(tenantA, Actor.user(ana), "x", Set.of(Permission.API_KEYS_MANAGE), null)).code());
        assertThrows(DomainException.class, () -> apiKeys().issue(tenantA, Actor.user(ana), "x", Set.of(), null));
    }
}
