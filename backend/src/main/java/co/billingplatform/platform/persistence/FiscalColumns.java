package co.billingplatform.platform.persistence;

import co.billingplatform.shared.domain.FiscalProfile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;

/**
 * Perfil tributario en columnas {@code person_type}, {@code vat_regime} y
 * {@code tax_responsibilities} (códigos separados por coma). Las tres van juntas: la base lo
 * exige con una restricción (V16).
 */
public final class FiscalColumns {

    private FiscalColumns() {}

    public static void put(Map<String, Object> params, FiscalProfile fiscal) {
        params.put("personType", fiscal == null ? null : fiscal.personType().name());
        params.put("vatRegime", fiscal == null ? null : fiscal.vatRegime().name());
        params.put("responsibilities", fiscal == null ? null : String.join(",", fiscal.responsibilityCodes()));
    }

    public static FiscalProfile read(ResultSet rs) throws SQLException {
        String personType = rs.getString("person_type");
        if (personType == null) {
            return null;
        }
        return FiscalProfile.of(FiscalProfile.PersonType.valueOf(personType),
                FiscalProfile.VatRegime.valueOf(rs.getString("vat_regime")),
                Arrays.asList(rs.getString("tax_responsibilities").split(",")));
    }
}
