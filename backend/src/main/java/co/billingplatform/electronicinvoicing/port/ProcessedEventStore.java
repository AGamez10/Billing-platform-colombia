package co.billingplatform.electronicinvoicing.port;

import co.billingplatform.electronicinvoicing.domain.ProviderId;

/** Registro de eventos de webhook ya procesados (tabla con clave única), para descartar repeticiones. */
public interface ProcessedEventStore {

    /** @return {@code true} si el evento es nuevo y quedó registrado; {@code false} si ya se procesó. */
    boolean markIfNew(ProviderId providerId, String eventId);
}
