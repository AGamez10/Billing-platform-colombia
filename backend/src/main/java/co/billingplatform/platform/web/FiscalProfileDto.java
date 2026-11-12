package co.billingplatform.platform.web;

import co.billingplatform.shared.domain.FiscalProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Perfil tributario en la API: {@code personType} LEGAL|NATURAL, {@code vatRegime}
 * RESPONSIBLE|NOT_RESPONSIBLE y {@code responsibilities} con los códigos del RUT
 * (O-13, O-15, O-23, O-47 o R-99-PN). La respuesta incluye además los códigos DIAN.
 */
public record FiscalProfileDto(
        @NotNull FiscalProfile.PersonType personType,
        @NotNull FiscalProfile.VatRegime vatRegime,
        @NotNull @Size(min = 1, max = 5) List<@NotNull @Size(max = 10) String> responsibilities,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Código DIAN del tipo de persona (1 jurídica, 2 natural)")
        String personTypeCode,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Código del régimen (48 responsable de IVA, 49 no responsable)")
        String vatRegimeCode) {

    public FiscalProfile toDomain() {
        return FiscalProfile.of(personType, vatRegime, responsibilities);
    }

    public static FiscalProfileDto of(FiscalProfile p) {
        return p == null ? null : new FiscalProfileDto(p.personType(), p.vatRegime(), p.responsibilityCodes(),
                p.personType().code(), p.vatRegime().code());
    }

    public static FiscalProfile toDomain(FiscalProfileDto dto) {
        return dto == null ? null : dto.toDomain();
    }
}
