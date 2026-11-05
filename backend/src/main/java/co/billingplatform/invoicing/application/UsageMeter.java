package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.util.UUID;

/**
 * Lo que la facturación necesita del módulo de suscripciones. Lo implementa
 * {@code subscriptions}; facturación no conoce planes ni precios.
 */
public interface UsageMeter {

    /**
     * Verifica que el tenant pueda emitir un documento más en su periodo actual. La
     * implementación bloquea la suscripción durante la transacción para que dos
     * finalizaciones concurrentes no superen juntas el límite.
     *
     * @throws DomainException {@code SUBSCRIPTION_REQUIRED}, {@code SUBSCRIPTION_INACTIVE}
     *                         o {@code DOCUMENT_QUOTA_EXCEEDED}
     */
    void ensureCanIssue(TenantId tenantId);

    /** Registra el consumo; idempotente por documento. */
    void recordIssued(TenantId tenantId, UUID documentId, ProviderAccount account);
}
