package co.billingplatform.tax.domain;

/**
 * Tipos de tributo soportados por el motor.
 *
 * <p>Los códigos que exige el formato UBL/DIAN para cada tributo NO se modelan aquí:
 * pertenecen a los adaptadores de proveedor o a un mapeo de formato, para que el
 * dominio no dependa de un esquema externo.
 */
public enum TaxType {
    /** Impuesto sobre las ventas (Estatuto Tributario, Libro III). */
    IVA,
    /** Impuesto nacional al consumo (Estatuto Tributario, art. 512-1 y ss.). */
    INC
}
