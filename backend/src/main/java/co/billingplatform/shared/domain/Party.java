package co.billingplatform.shared.domain;

import java.util.Objects;

/**
 * Datos de una parte copiados en el documento al momento de emitirlo (snapshot).
 * El documento no referencia el registro vivo del cliente: si el cliente cambia
 * después, el documento emitido conserva los datos con los que se expidió.
 *
 * <p>{@code fiscal} y {@code address} pueden faltar en documentos de práctica (simulación) o en
 * datos anteriores a V16; los ambientes reales exigen el perfil tributario antes de emitir.
 */
public record Party(PartyIdentification identification, String legalName, String email,
                    FiscalProfile fiscal, FiscalAddress address) {

    public Party(PartyIdentification identification, String legalName, String email) {
        this(identification, legalName, email, null, null);
    }

    /** Identificación genérica que usa la DIAN para el comprador no identificado (consumidor final). */
    public static final String FINAL_CONSUMER_ID = "222222222222";

    /**
     * Comprador no identificado: venta al público sin datos del comprador. Un documento a nombre
     * del consumidor final no sirve al comprador para deducciones; para eso debe identificarse.
     */
    public static Party finalConsumer() {
        return new Party(new PartyIdentification(IdentificationType.CEDULA_CIUDADANIA, FINAL_CONSUMER_ID, null),
                "Consumidor final", null, FiscalProfile.naturalNotResponsible(), null);
    }

    public boolean isFinalConsumer() {
        return FINAL_CONSUMER_ID.equals(identification.number());
    }

    public java.util.Optional<FiscalProfile> fiscalProfile() {
        return java.util.Optional.ofNullable(fiscal);
    }

    public java.util.Optional<FiscalAddress> fiscalAddress() {
        return java.util.Optional.ofNullable(address);
    }

    public Party {
        Objects.requireNonNull(identification, "identification is required");
        if (legalName == null || legalName.isBlank()) {
            throw new DomainException("INVALID_PARTY", "Legal name is required");
        }
        legalName = legalName.strip();
        if (legalName.length() > 450) {
            throw new DomainException("INVALID_PARTY", "Legal name is too long");
        }
        email = (email == null || email.isBlank()) ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
        if (email != null && (email.length() > 254 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            throw new DomainException("INVALID_PARTY", "Email is not valid");
        }
    }
}
