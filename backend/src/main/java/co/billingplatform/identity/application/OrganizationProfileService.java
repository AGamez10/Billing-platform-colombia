package co.billingplatform.identity.application;

import co.billingplatform.identity.domain.OrganizationProfile;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.Municipalities;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalAddress;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.TenantId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Datos del emisor: razón social, contacto, perfil tributario y dirección fiscal. */
public final class OrganizationProfileService {

    public interface Profiles {
        Optional<OrganizationProfile> find(TenantId tenantId);

        void update(OrganizationProfile profile);
    }

    public record Changes(String legalName, String tradeName, String email, String phone, FiscalProfile fiscal,
                          String addressLine, String cityCode) {}

    private final Profiles profiles;
    private final Municipalities municipalities;
    private final AuditTrail audit;

    public OrganizationProfileService(Profiles profiles, Municipalities municipalities, AuditTrail audit) {
        this.profiles = Objects.requireNonNull(profiles);
        this.municipalities = Objects.requireNonNull(municipalities);
        this.audit = Objects.requireNonNull(audit);
    }

    public OrganizationProfile get(TenantId tenantId) {
        return profiles.find(tenantId)
                .orElseThrow(() -> new DomainException("ORGANIZATION_NOT_FOUND", "Organization not found"));
    }

    public OrganizationProfile update(TenantId tenantId, Actor actor, Changes c) {
        OrganizationProfile current = get(tenantId);
        if (c.fiscal() == null) {
            throw new DomainException("INVALID_FISCAL_PROFILE", "The issuer's tax profile is required");
        }
        FiscalAddress address = FiscalAddress.ofNullable(c.addressLine(), c.cityCode());
        if (address == null) {
            throw new DomainException("INVALID_ADDRESS", "The issuer's address and municipality are required");
        }
        municipalities.requireKnown(address.cityCode());
        if (c.email() == null || c.email().isBlank()) {
            throw new DomainException("INVALID_PARTY", "The issuer's email is required");
        }
        OrganizationProfile updated = new OrganizationProfile(tenantId, current.identification(), c.legalName(),
                c.tradeName(), c.email(), c.phone(), c.fiscal(), address);
        profiles.update(updated);
        audit.record(new AuditTrail.Event(tenantId, actor, "ORGANIZATION_PROFILE_UPDATED", "organization",
                tenantId.value().toString(), Map.of(
                        "legalNameChanged", String.valueOf(!current.legalName().equals(updated.legalName())),
                        "responsibilities", String.join(",", updated.fiscal().responsibilityCodes()),
                        "vatRegime", updated.fiscal().vatRegime().code())));
        return updated;
    }
}
