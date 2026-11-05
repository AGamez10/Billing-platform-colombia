package co.billingplatform.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Regla explícita de redondeo monetario.
 *
 * <p>La política por defecto (2 decimales, HALF_UP, redondeo por línea) es una decisión
 * de producto documentada en docs/DECISIONS.md (ADR-0005). Está PENDIENTE de
 * contrastarse con las reglas de validación y tolerancias del Anexo Técnico vigente de
 * la DIAN y con el proveedor tecnológico elegido; por eso es un valor inyectable y no
 * una constante dispersa en el código.
 */
public record RoundingPolicy(int scale, RoundingMode mode) {

    public static final RoundingPolicy COP_DEFAULT = new RoundingPolicy(2, RoundingMode.HALF_UP);

    public RoundingPolicy {
        Objects.requireNonNull(mode);
        if (scale < 0 || scale > 6) {
            throw new IllegalArgumentException("unsupported monetary scale: " + scale);
        }
        if (mode == RoundingMode.UNNECESSARY) {
            throw new IllegalArgumentException("UNNECESSARY is not a valid monetary rounding mode");
        }
    }

    public BigDecimal apply(BigDecimal amount) {
        return amount.setScale(scale, mode);
    }

    public BigDecimal zero() {
        return BigDecimal.ZERO.setScale(scale, mode);
    }
}
