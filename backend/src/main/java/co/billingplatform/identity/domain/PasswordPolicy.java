package co.billingplatform.identity.domain;

import co.billingplatform.shared.domain.DomainException;

import java.util.Locale;

/**
 * Política de contraseñas alineada con la guía de OWASP/NIST: longitud mínima en vez de
 * reglas de composición, y longitud máxima para acotar el costo de Argon2 (DoS).
 * Verificación contra contraseñas filtradas: pendiente (ROADMAP).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static void validate(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new DomainException("WEAK_PASSWORD", "Password must have at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw new DomainException("WEAK_PASSWORD", "Password must have at most " + MAX_LENGTH + " characters");
        }
        if (password.chars().distinct().count() < 4) {
            throw new DomainException("WEAK_PASSWORD", "Password is too repetitive");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
            if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) {
                throw new DomainException("WEAK_PASSWORD", "Password must not contain the email address");
            }
        }
    }
}
