package co.billingplatform.electronicinvoicing.domain;

/** Identificador que el proveedor asigna a un documento recibido. Opaco para el dominio. */
public record ExternalReference(String value) {

    public ExternalReference {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("invalid external reference");
        }
    }
}
