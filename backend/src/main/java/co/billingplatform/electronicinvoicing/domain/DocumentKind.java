package co.billingplatform.electronicinvoicing.domain;

public enum DocumentKind {
    /** Factura electrónica de venta. */
    INVOICE,
    /**
     * Documento equivalente electrónico tiquete de máquina registradora con sistema POS
     * (Resolución DIAN 000165 de 2023). Lo emite el comercio por cada venta; tiene su propia
     * resolución de numeración y su código único se llama CUDE.
     */
    POS_EQUIVALENT,
    CREDIT_NOTE,
    DEBIT_NOTE
}
