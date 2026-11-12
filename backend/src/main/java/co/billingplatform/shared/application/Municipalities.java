package co.billingplatform.shared.application;

import co.billingplatform.shared.domain.DomainException;

import java.util.List;
import java.util.Optional;

/** Catálogo oficial de municipios de Colombia (DIVIPOLA del DANE). */
public interface Municipalities {

    record Municipality(String code, String name, String departmentCode, String departmentName) {}

    Optional<Municipality> find(String code);

    List<Municipality> all();

    /** El código debe existir en la DIVIPOLA; {@code null} se acepta (dato opcional). */
    default void requireKnown(String code) {
        if (code != null && find(code).isEmpty()) {
            throw new DomainException("UNKNOWN_MUNICIPALITY", "City code is not in the DANE DIVIPOLA catalog");
        }
    }
}
