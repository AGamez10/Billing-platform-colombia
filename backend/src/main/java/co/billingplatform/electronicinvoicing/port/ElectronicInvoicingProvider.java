package co.billingplatform.electronicinvoicing.port;

import co.billingplatform.electronicinvoicing.domain.ElectronicDocument;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.IdempotencyKey;
import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderCapabilities;
import co.billingplatform.electronicinvoicing.domain.ProviderEvent;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;

import java.util.Optional;

/**
 * Contrato común con los proveedores tecnológicos (puerto de salida).
 *
 * <p>Diferencias deliberadas frente a la propuesta inicial (ver ADR-0004):
 * <ul>
 *   <li>Toda operación recibe la {@link ProviderAccount} del tenant: sin ella el
 *       adaptador no sabría con qué credenciales ni en qué ambiente actuar.</li>
 *   <li>Un solo {@code submit} para factura y notas; el tipo va en el documento y la
 *       capacidad se valida antes de llamar.</li>
 *   <li>El webhook no se "maneja" dentro del adaptador: el adaptador solo lo
 *       <em>verifica y traduce</em>; los cambios de estado los aplica el núcleo.</li>
 *   <li>Los resultados son un tipo cerrado ({@link SubmissionResult}); las implementaciones
 *       no deben propagar excepciones por fallas del proveedor, sino traducirlas.</li>
 * </ul>
 */
public interface ElectronicInvoicingProvider {

    ProviderId id();

    ProviderCapabilities capabilities();

    /** Envía un documento. Debe ser seguro reintentarlo con la misma clave si {@code idempotentSubmission}. */
    SubmissionResult submit(ProviderAccount account, ElectronicDocument document, IdempotencyKey key);

    /** Solo si {@code capabilities().statusQuery()}. */
    SubmissionResult queryStatus(ProviderAccount account, ExternalReference reference);

    /** Solo si {@code capabilities().lookupByDocumentNumber()}. Vacío si el proveedor no lo conoce. */
    default Optional<SubmissionResult> findByDocumentNumber(ProviderAccount account, String fullDocumentNumber) {
        throw new UnsupportedOperationException(id() + " does not support lookup by document number");
    }

    /**
     * Verifica autenticidad (firma, marca de tiempo) y traduce el webhook.
     *
     * @throws WebhookVerificationException si la firma no es válida o el evento está vencido
     */
    ProviderEvent parseWebhook(ProviderAccount account, InboundWebhook webhook);
}
