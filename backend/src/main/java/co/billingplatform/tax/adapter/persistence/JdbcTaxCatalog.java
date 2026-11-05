package co.billingplatform.tax.adapter.persistence;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.tax.domain.TaxCatalog;
import co.billingplatform.tax.domain.TaxRate;
import co.billingplatform.tax.domain.TaxTreatment;
import co.billingplatform.tax.domain.TaxType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Catálogo tributario desde la tabla {@code tax_rates} (vigencias sin solapamiento garantizadas por la base). */
@Repository
public class JdbcTaxCatalog implements TaxCatalog {

    private final JdbcClient jdbc;

    public JdbcTaxCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TaxRate resolve(String code, LocalDate onDate) {
        List<TaxRate> rates = jdbc.sql("""
                        SELECT * FROM tax_rates
                        WHERE code = :code AND valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                        """)
                .param("code", code)
                .param("d", Date.valueOf(onDate))
                .query((rs, i) -> map(rs))
                .list();
        if (rates.size() != 1) {
            throw new DomainException("TAX_RATE_NOT_FOUND",
                    "No single tax rate '%s' valid on %s (found %d)".formatted(code, onDate, rates.size()));
        }
        return rates.getFirst();
    }

    /** Tarifas vigentes en la fecha dada. */
    public List<TaxRate> validOn(LocalDate onDate) {
        return jdbc.sql("""
                        SELECT * FROM tax_rates
                        WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to >= :d)
                        ORDER BY tax_type, percentage DESC, code
                        """)
                .param("d", Date.valueOf(onDate))
                .query((rs, i) -> map(rs))
                .list();
    }

    private static TaxRate map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date validTo = rs.getDate("valid_to");
        return new TaxRate(
                rs.getString("code"),
                TaxType.valueOf(rs.getString("tax_type")),
                TaxTreatment.valueOf(rs.getString("treatment")),
                rs.getBigDecimal("percentage"),
                rs.getDate("valid_from").toLocalDate(),
                validTo == null ? null : validTo.toLocalDate(),
                rs.getString("legal_reference"));
    }
}
