package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.util.UUID;

/**
 * Lo que la facturación necesita del módulo de suscripciones. Lo implementa
 * {@code subscriptions}; facturación no conoce planes ni precios.
 */
public interface UsageMeter {

    /**
     * Verifica que el tenant pueda emitir un documento más en el ambiente dado. Solo PRODUCTION
     * consume cuota. La implementación bloquea la suscripción durante la transacción para que
     * dos finalizaciones concurrentes no superen juntas el límite.
     *
     * @throws DomainException {@code SUBSCRIPTION_REQUIRED}, {@code SUBSCRIPTION_INACTIVE},
     *         {@code TRIAL_QUOTA_EXHAUSTED}, {@code DOCUMENT_QUOTA_EXCEEDED} o
     *         {@code DOCUMENT_QUOTA_REACHED} (se debe ofrecer mejorar el plan o aceptar excedentes)
     */
    void ensureCanIssue(TenantId tenantId, ProviderEnvironment environment);

    /** Registra el consumo (y si es excedente cobrable); idempotente por documento. */
    void recordIssued(TenantId tenantId, UUID documentId, ProviderAccount account);
}
