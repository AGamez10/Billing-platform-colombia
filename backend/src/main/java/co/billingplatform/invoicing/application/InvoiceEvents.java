package co.billingplatform.invoicing.application;

import co.billingplatform.invoicing.domain.Invoice;

/**
 * Un documento llegó a un estado final (aceptado, rechazado o fallido). Se invoca dentro de la
 * misma transacción que guarda el estado: el aviso a otras apps queda en la bandeja de salida
 * solo si el cambio se confirma (outbox transaccional).
 */
@FunctionalInterface
public interface InvoiceEvents {

    InvoiceEvents NONE = invoice -> { };

    void reachedFinalStatus(Invoice invoice);
}
