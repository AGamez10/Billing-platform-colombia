package co.billingplatform.tax.domain;

import co.billingplatform.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Catálogo de tarifas vigentes. Puerto: la implementación productiva lee la tabla
 * {@code tax_rates} (ver migración V1); las pruebas usan {@link #of(List)}.
 */
public interface TaxCatalog {

    /**
     * @throws DomainException {@code TAX_RATE_NOT_FOUND} si no hay una única tarifa
     *                         con ese código vigente en la fecha dada.
     */
    TaxRate resolve(String code, LocalDate onDate);

    static TaxCatalog of(List<TaxRate> rates) {
        List<TaxRate> snapshot = List.copyOf(rates);
        return (code, onDate) -> {
            Objects.requireNonNull(code);
            Objects.requireNonNull(onDate);
            List<TaxRate> matches = snapshot.stream()
                    .filter(r -> r.code().equals(code) && r.isValidOn(onDate))
                    .toList();
            if (matches.size() != 1) {
                throw new DomainException("TAX_RATE_NOT_FOUND",
                        "No single tax rate '%s' valid on %s (found %d)".formatted(code, onDate, matches.size()));
            }
            return matches.getFirst();
        };
    }
}
