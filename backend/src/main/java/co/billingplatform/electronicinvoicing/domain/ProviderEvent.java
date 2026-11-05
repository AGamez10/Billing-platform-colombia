package co.billingplatform.electronicinvoicing.domain;

import java.util.Objects;

/**
 * Evento de webhook ya verificado y normalizado.
 *
 * @param eventId identificador único del evento en el proveedor, para descartar duplicados
 */
public record ProviderEvent(String eventId, ExternalReference reference, SubmissionResult outcome) {

    public ProviderEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        Objects.requireNonNull(reference);
        Objects.requireNonNull(outcome);
    }
}
