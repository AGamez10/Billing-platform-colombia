package co.billingplatform.shared.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Perfil tributario de una parte (emisor o adquiriente): tipo de persona, régimen frente al IVA y
 * responsabilidades fiscales del RUT.
 *
 * <p>Códigos verificados el 2026-11-12 en el anexo DIAN "Reglas activas 1 agosto 2020" (lista de
 * responsabilidades vigente para facturación electrónica) y en la documentación de Muisca/Aliaddo
 * (tipo de persona 1/2, régimen 48/49). Son códigos del estándar, no de un proveedor: cada
 * adaptador decide cómo los envía.
 */
public record FiscalProfile(PersonType personType, VatRegime vatRegime, Set<TaxResponsibility> responsibilities) {

    public enum PersonType {
        LEGAL("1"),
        NATURAL("2");

        private final String code;

        PersonType(String code) { this.code = code; }

        public String code() { return code; }
    }

    public enum VatRegime {
        RESPONSIBLE("48"),
        NOT_RESPONSIBLE("49");

        private final String code;

        VatRegime(String code) { this.code = code; }

        public String code() { return code; }
    }

    /** Responsabilidades del RUT que admite la factura electrónica. */
    public enum TaxResponsibility {
        GRAN_CONTRIBUYENTE("O-13"),
        AUTORRETENEDOR("O-15"),
        AGENTE_RETENCION_IVA("O-23"),
        REGIMEN_SIMPLE("O-47"),
        /** "No aplica – Otros": para quien no tiene ninguna de las anteriores. Excluye a las demás. */
        NO_RESPONSABLE("R-99-PN");

        private final String code;

        TaxResponsibility(String code) { this.code = code; }

        public String code() { return code; }

        public static TaxResponsibility ofCode(String code) {
            String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
            for (TaxResponsibility r : values()) {
                if (r.code.equals(c)) {
                    return r;
                }
            }
            throw new DomainException("INVALID_FISCAL_PROFILE", "Unknown tax responsibility: " + code);
        }
    }

    public FiscalProfile {
        if (personType == null || vatRegime == null) {
            throw new DomainException("INVALID_FISCAL_PROFILE", "Person type and VAT regime are required");
        }
        if (responsibilities == null || responsibilities.isEmpty()) {
            throw new DomainException("INVALID_FISCAL_PROFILE", "At least one tax responsibility is required");
        }
        if (responsibilities.contains(TaxResponsibility.NO_RESPONSABLE) && responsibilities.size() > 1) {
            throw new DomainException("INVALID_FISCAL_PROFILE", "R-99-PN cannot be combined with other responsibilities");
        }
        responsibilities = Set.copyOf(EnumSet.copyOf(responsibilities));
    }

    /** El comprador no identificado o una persona sin responsabilidades especiales. */
    public static FiscalProfile naturalNotResponsible() {
        return new FiscalProfile(PersonType.NATURAL, VatRegime.NOT_RESPONSIBLE, Set.of(TaxResponsibility.NO_RESPONSABLE));
    }

    public static FiscalProfile of(PersonType personType, VatRegime vatRegime, Collection<String> responsibilityCodes) {
        Objects.requireNonNull(responsibilityCodes, "responsibilities are required");
        return new FiscalProfile(personType, vatRegime, responsibilityCodes.stream()
                .map(TaxResponsibility::ofCode)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TaxResponsibility.class))));
    }

    /** Códigos en orden estable (para persistir y mostrar). */
    public java.util.List<String> responsibilityCodes() {
        return EnumSet.copyOf(responsibilities).stream().map(TaxResponsibility::code).toList();
    }
}
