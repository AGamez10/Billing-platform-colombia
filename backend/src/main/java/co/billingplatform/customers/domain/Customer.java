package co.billingplatform.customers.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Cliente (adquiriente) de una organización. Inmutable: cada cambio produce una nueva versión
 * con {@code version + 1} para el bloqueo optimista.
 */
public record Customer(
        UUID id,
        TenantId tenantId,
        PartyIdentification identification,
        String legalName,
        String email,
        String phone,
        String address,
        String cityCode,
        FiscalProfile fiscal,
        boolean active,
        int version) {

    public Customer {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        // Reutiliza las validaciones de parte (identificación con DV, nombre, correo).
        Party party = new Party(identification, legalName, email);
        legalName = party.legalName();
        email = party.email();
        phone = optional(phone, 30, "^[0-9+()\\-\\s]{5,30}$", "Phone is not valid");
        address = optional(address, 300, null, "Address is too long");
        cityCode = optional(cityCode, 10, "^[0-9]{5}$", "City code must be the 5-digit DANE code");
    }

    public static Customer create(TenantId tenantId, PartyIdentification identification, String legalName, String email,
                                  String phone, String address, String cityCode, FiscalProfile fiscal) {
        return new Customer(UUID.randomUUID(), tenantId, identification, legalName, email, phone, address, cityCode, fiscal,
                true, 0);
    }

    public Customer withDetails(PartyIdentification identification, String legalName, String email,
                                String phone, String address, String cityCode, FiscalProfile fiscal) {
        return new Customer(id, tenantId, identification, legalName, email, phone, address, cityCode, fiscal, active,
                version + 1);
    }

    public Customer deactivated() {
        return new Customer(id, tenantId, identification, legalName, email, phone, address, cityCode, fiscal, false,
                version + 1);
    }

    /** Snapshot para el documento; la dirección solo viaja completa (línea y municipio). */
    public Party asParty() {
        FiscalAddress fiscalAddress = address != null && address.length() >= 3 && cityCode != null
                ? new FiscalAddress(address, cityCode) : null;
        return new Party(identification, legalName, email, fiscal, fiscalAddress);
    }

    private static String optional(String value, int max, String pattern, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max || (pattern != null && !v.matches(pattern))) {
            throw new DomainException("INVALID_CUSTOMER", message);
        }
        return v;
    }
}
