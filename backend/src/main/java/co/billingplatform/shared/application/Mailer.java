package co.billingplatform.shared.application;

import java.util.Objects;

/**
 * Correo transaccional (recuperar contraseña, invitaciones). Independiente del proveedor: SMTP
 * estándar en producción (Amazon SES u otro), un buzón local en desarrollo y uno en memoria en
 * las pruebas.
 *
 * <p>El envío ocurre después de confirmar la transacción y en segundo plano: un correo nunca
 * sale por una operación que se deshizo, y el tiempo de respuesta no revela si se envió algo
 * (anti-enumeración de cuentas).
 */
public interface Mailer {

    /** @param html versión HTML; {@code text} es la alternativa en texto plano y siempre se envía */
    record Email(String to, String subject, String text, String html) {
        public Email {
            Objects.requireNonNull(to);
            Objects.requireNonNull(subject);
            Objects.requireNonNull(text);
            if (to.isBlank() || to.contains("\r") || to.contains("\n") || subject.contains("\r") || subject.contains("\n")) {
                throw new IllegalArgumentException("Invalid email header");
            }
        }

        /** El cuerpo puede llevar enlaces de un solo uso: nunca se imprime. */
        @Override
        public String toString() {
            return "Email[to=" + to + ", subject=" + subject + "]";
        }
    }

    void send(Email email);
}
