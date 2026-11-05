package co.billingplatform.shared.domain;

import java.util.Objects;

/**
 * Violación de una regla de negocio. Lleva un código estable que la capa web traduce
 * a una respuesta de error (RFC 9457) sin exponer detalles internos.
 */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code);
    }

    public String code() {
        return code;
    }
}
