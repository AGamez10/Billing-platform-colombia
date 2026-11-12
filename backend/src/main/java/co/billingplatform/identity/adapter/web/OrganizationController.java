package co.billingplatform.identity.adapter.web;

import co.billingplatform.identity.application.OrganizationProfileService;
import co.billingplatform.identity.domain.OrganizationProfile;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.platform.web.FiscalProfileDto;
import co.billingplatform.shared.application.Municipalities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Datos del emisor (la organización del usuario autenticado; nunca la que diga el cliente). */
@RestController
@RequestMapping("/api/v1/organization")
@Tag(name = "Organización")
@SecurityRequirement(name = "bearer")
class OrganizationController {

    record AddressView(String line, String cityCode, String cityName, String departmentCode, String departmentName) {}

    record ProfileView(String identificationType, String identificationNumber, Integer checkDigit, String legalName,
                       String tradeName, String email, String phone, FiscalProfileDto fiscal, AddressView address,
                       boolean readyToIssue) {}

    record ProfileRequest(
            @NotBlank @Size(max = 450) String legalName,
            @Size(max = 450) String tradeName,
            @NotBlank @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @NotNull @Valid FiscalProfileDto fiscal,
            @NotBlank @Size(max = 300) String addressLine,
            @NotBlank @Size(max = 5) String cityCode) {}

    private final OrganizationProfileService profiles;
    private final Municipalities municipalities;
    private final TenantTransactions tx;

    OrganizationController(OrganizationProfileService profiles, Municipalities municipalities, TenantTransactions tx) {
        this.profiles = profiles;
        this.municipalities = municipalities;
        this.tx = tx;
    }

    @GetMapping
    @Operation(summary = "Datos del emisor. Cualquier miembro puede verlos.")
    ProfileView get(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        return tx.inTenant(u.tenantId(), () -> view(profiles.get(u.tenantId())));
    }

    @PutMapping
    @Operation(summary = "Actualizar razón social, contacto, perfil tributario y dirección fiscal. Dueño o administrador.")
    ProfileView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.ORGANIZATION_MANAGE);
        return tx.inTenant(u.tenantId(), () -> view(profiles.update(u.tenantId(), u.actor(),
                new OrganizationProfileService.Changes(r.legalName(), r.tradeName(), r.email(), r.phone(),
                        r.fiscal().toDomain(), r.addressLine(), r.cityCode()))));
    }

    private ProfileView view(OrganizationProfile p) {
        AddressView address = p.address() == null ? null : municipalities.find(p.address().cityCode())
                .map(m -> new AddressView(p.address().line(), m.code(), m.name(), m.departmentCode(), m.departmentName()))
                .orElse(new AddressView(p.address().line(), p.address().cityCode(), null, p.address().departmentCode(), null));
        return new ProfileView(p.identification().type().name(), p.identification().number(),
                p.identification().checkDigit(), p.legalName(), p.tradeName(), p.email(), p.phone(),
                FiscalProfileDto.of(p.fiscal()), address, p.readyToIssue());
    }
}
