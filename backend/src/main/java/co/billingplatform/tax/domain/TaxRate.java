package co.billingplatform.tax.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Tarifa de un tributo con vigencia.
 *
 * <p>Las tarifas son datos, no código: hay una reforma tributaria en trámite (oct-2026)
 * que propone mover bienes entre tarifas. Cada tarifa registra su fundamento legal y
 * su ventana de vigencia, y el cálculo resuelve la tarifa por fecha de emisión.
 *
 * @param code           código interno estable, p. ej. {@code IVA_19}
 * @param validTo        fin de vigencia inclusivo, o {@code null} si está vigente
 * @param legalReference norma que soporta la tarifa (obligatorio, nunca inventado)
 */
public record TaxRate(
        String code,
        TaxType type,
        TaxTreatment treatment,
        BigDecimal percentage,
        LocalDate validFrom,
        LocalDate validTo,
        String legalReference) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public TaxRate {
        Objects.requireNonNull(code);
        Objects.requireNonNull(type);
        Objects.requireNonNull(treatment);
        Objects.requireNonNull(percentage);
        Objects.requireNonNull(validFrom);
        if (legalReference == null || legalReference.isBlank()) {
            throw new IllegalArgumentException("a tax rate must cite its legal reference: " + code);
        }
        if (percentage.signum() < 0 || percentage.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("percentage out of range for " + code);
        }
        if (treatment != TaxTreatment.TAXED && percentage.signum() != 0) {
            throw new IllegalArgumentException("exempt/excluded rates must be 0%: " + code);
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo before validFrom for " + code);
        }
    }

    public boolean isValidOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    /** Fracción a aplicar sobre la base (p. ej. 19 → 0.19), sin redondeo. */
    public BigDecimal fraction() {
        return percentage.movePointLeft(2);
    }
}
