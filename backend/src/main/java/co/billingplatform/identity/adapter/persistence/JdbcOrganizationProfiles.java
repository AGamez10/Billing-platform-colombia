package co.billingplatform.identity.adapter.persistence;

import co.billingplatform.identity.application.OrganizationProfileService;
import co.billingplatform.identity.domain.OrganizationProfile;
import co.billingplatform.platform.persistence.FiscalColumns;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code organizations} con RLS: cada organización solo se ve a sí misma. */
@Repository
public class JdbcOrganizationProfiles implements OrganizationProfileService.Profiles {

    private final JdbcClient jdbc;

    JdbcOrganizationProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrganizationProfile> find(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM organizations WHERE id = :t")
                .param("t", tenantId.value())
                .query(JdbcOrganizationProfiles::map)
                .optional();
    }

    @Override
    public void update(OrganizationProfile p) {
        Map<String, Object> params = new HashMap<>();
        params.put("t", p.tenantId().value());
        params.put("name", p.legalName());
        params.put("trade", p.tradeName());
        params.put("email", p.email());
        params.put("phone", p.phone());
        params.put("line", p.address() == null ? null : p.address().line());
        params.put("city", p.address() == null ? null : p.address().cityCode());
        FiscalColumns.put(params, p.fiscal());
        jdbc.sql("""
                        UPDATE organizations
                        SET legal_name = :name, trade_name = :trade, email = :email, phone = :phone,
                            address_line = :line, city_code = :city, person_type = :personType,
                            vat_regime = :vatRegime, tax_responsibilities = :responsibilities, updated_at = now()
                        WHERE id = :t
                        """)
                .params(params)
                .update();
    }

    static OrganizationProfile map(ResultSet rs, int n) throws SQLException {
        Object dv = rs.getObject("check_digit");
        String line = rs.getString("address_line");
        String city = rs.getString("city_code");
        return new OrganizationProfile(
                new TenantId(rs.getObject("id", UUID.class)),
                new PartyIdentification(IdentificationType.valueOf(rs.getString("id_type")), rs.getString("id_number"),
                        dv == null ? null : ((Number) dv).intValue()),
                rs.getString("legal_name"),
                rs.getString("trade_name"),
                rs.getString("email"),
                rs.getString("phone"),
                FiscalColumns.read(rs),
                line == null || city == null ? null : new FiscalAddress(line, city));
    }
}
