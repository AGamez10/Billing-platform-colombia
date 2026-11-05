package co.billingplatform.tax.domain;

/**
 * Tratamiento tributario de una tarifa.
 *
 * <p>En IVA no es lo mismo un bien <em>exento</em> (gravado a tarifa 0%) que uno
 * <em>excluido</em> (no causa el impuesto). Ambos producen impuesto cero, pero se
 * reportan distinto en el documento electrónico; por eso la distinción es explícita y
 * no se infiere de un porcentaje igual a cero.
 */
public enum TaxTreatment {
    TAXED,
    EXEMPT,
    EXCLUDED
}
