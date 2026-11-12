package co.billingplatform.identity.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;

import java.util.Objects;

/**
 * Datos del emisor. La identificación (NIT o documento con el que se registró) no cambia: es la
 * identidad tributaria de la organización; cambiarla es otra organización.
 */
public record OrganizationProfile(
        TenantId tenantId,
        PartyIdentification identification,
        String legalName,
        String tradeName,
        String email,
        String phone,
        FiscalProfile fiscal,
        FiscalAddress address) {

    public OrganizationProfile {
        Objects.requireNonNull(tenantId);
        Party party = new Party(identification, legalName, email, fiscal, address); // mismas reglas que una parte
        legalName = party.legalName();
        email = party.email();
        tradeName = tradeName == null || tradeName.isBlank() ? null : tradeName.strip();
        if (tradeName != null && tradeName.length() > 450) {
            throw new DomainException("INVALID_ORGANIZATION", "Trade name is too long");
        }
        phone = phone == null || phone.isBlank() ? null : phone.strip();
        if (phone != null && !phone.matches("^[0-9+()\\-\\s]{5,30}$")) {
            throw new DomainException("INVALID_ORGANIZATION", "Phone is not valid");
        }
    }

    /** Lo mínimo para emitir en un ambiente real: perfil tributario, dirección y correo. */
    public boolean readyToIssue() {
        return fiscal != null && address != null && email != null;
    }

    public Party asParty() {
        return new Party(identification, legalName, email, fiscal, address);
    }
}
