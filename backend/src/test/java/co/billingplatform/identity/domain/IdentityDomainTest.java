package co.billingplatform.identity.domain;

import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.shared.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class IdentityDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    @DisplayName("política de contraseñas: longitud, repetición y correo")
    void passwordPolicy() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("caballo bateria grapa", "ana@x.co"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("corta123", "ana@x.co"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("aaaaaaaaaaaaaaaa", "ana@x.co"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("x".repeat(129) + "abc", "ana@x.co"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate("MiCorreoEsCarlitos2026", "carlitos@x.co"));
        assertThrows(DomainException.class, () -> PasswordPolicy.validate(null, null));
    }

    @Test
    @DisplayName("bloqueo progresivo: libre hasta 4 fallos, 15 min al 5.º, se duplica y se limita a 24 h")
    void loginThrottle() {
        assertTrue(LoginThrottlePolicy.lockUntil(4, NOW).isEmpty());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), LoginThrottlePolicy.lockUntil(5, NOW).orElseThrow());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), LoginThrottlePolicy.lockUntil(6, NOW).orElseThrow());
        assertEquals(NOW.plus(Duration.ofHours(24)), LoginThrottlePolicy.lockUntil(50, NOW).orElseThrow());
    }

    @Test
    @DisplayName("roles: permisos y prevención de escalada al asignar roles")
    void roles() {
        assertTrue(Role.OWNER.can(Permission.PROVIDER_MANAGE));
        assertFalse(Role.ADMIN.can(Permission.PROVIDER_MANAGE));
        assertTrue(Role.BILLER.can(Permission.INVOICE_FINALIZE));
        assertFalse(Role.BILLER.can(Permission.USERS_MANAGE));
        assertFalse(Role.VIEWER.can(Permission.INVOICE_WRITE));

        assertTrue(Role.OWNER.canGrant(Role.OWNER));
        assertTrue(Role.ADMIN.canGrant(Role.BILLER));
        assertFalse(Role.ADMIN.canGrant(Role.OWNER), "un admin no puede crear dueños");
        assertFalse(Role.BILLER.canGrant(Role.VIEWER), "sin USERS_MANAGE no asigna roles");
    }

    @Test
    @DisplayName("tokens opacos: aleatorios, almacenados como hash y comparados en tiempo constante")
    void opaqueTokens() {
        String a = OpaqueTokens.randomBase64Url(32);
        String b = OpaqueTokens.randomBase64Url(32);
        assertEquals(43, a.length());
        assertNotEquals(a, b);
        assertTrue(OpaqueTokens.matches(OpaqueTokens.sha256(a), a));
        assertFalse(OpaqueTokens.matches(OpaqueTokens.sha256(a), b));
    }
}
