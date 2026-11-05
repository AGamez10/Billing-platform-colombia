package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.Subscription;

import java.util.Optional;

public interface SubscriptionRepository {

    /** Suscripción vigente con su plan, bloqueada hasta el fin de la transacción (serializa la verificación de cuota). */
    Optional<Subscription> lockCurrent(TenantId tenantId);
}
