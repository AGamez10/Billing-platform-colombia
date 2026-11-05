package co.billingplatform.invoicing.domain;

/**
 * Ciclo de vida de una factura, independiente de proveedor.
 *
 * <pre>
 * DRAFT ──finalize──▶ PENDING_SUBMISSION ──submit──▶ SUBMITTED ──▶ ACCEPTED
 *                         │   ▲ (falla transitoria       │     └──▶ REJECTED
 *                         │   └── sin recepción)         └──▶ FAILED
 *                         └──▶ ACCEPTED | REJECTED | FAILED
 * </pre>
 *
 * Una factura ACCEPTED no se anula: se afecta mediante nota crédito (mecanismo legal).
 * Ese flujo está en docs/ROADMAP.md.
 */
public enum InvoiceStatus {
    /** Editable. Sin número; no consume numeración autorizada. */
    DRAFT,
    /** Numerada e inmutable, en cola para transmisión. */
    PENDING_SUBMISSION,
    /** Entregada (o posiblemente entregada) al proveedor; resultado final pendiente. */
    SUBMITTED,
    /** Validada, con evidencia. Solo tiene validez tributaria si el ambiente es PRODUCTION. */
    ACCEPTED,
    /** Rechazada por reglas de validación. */
    REJECTED,
    /** Falla no recuperable automáticamente; requiere intervención. */
    FAILED;

    public boolean isTerminal() {
        return this == ACCEPTED || this == REJECTED || this == FAILED;
    }
}
