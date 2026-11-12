package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.application.IssuerDirectory;
import co.billingplatform.platform.persistence.FiscalColumns;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Datos del emisor desde {@code organizations} (RLS: solo la propia organización es visible). */
@Repository
class JdbcIssuerDirectory implements IssuerDirectory {

    private final JdbcClient jdbc;

    JdbcIssuerDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Party issuerOf(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT id_type, id_number, check_digit, legal_name, email, address_line, city_code,
                               person_type, vat_regime, tax_responsibilities
                        FROM organizations WHERE id = :t
                        """)
                .param("t", tenantId.value())
                .query((rs, n) -> {
                    Object dv = rs.getObject("check_digit");
                    String line = rs.getString("address_line");
                    String city = rs.getString("city_code");
                    return new Party(new PartyIdentification(IdentificationType.valueOf(rs.getString("id_type")),
                            rs.getString("id_number"), dv == null ? null : ((Number) dv).intValue()),
                            rs.getString("legal_name"), rs.getString("email"), FiscalColumns.read(rs),
                            line == null || city == null ? null : new FiscalAddress(line, city));
                })
                .optional()
                .orElseThrow(() -> new DomainException("ORGANIZATION_NOT_FOUND", "Organization not found"));
    }
}
