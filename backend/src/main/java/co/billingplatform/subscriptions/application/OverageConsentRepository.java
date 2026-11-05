package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.OverageConsent;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Tabla {@code overage_consents}: una aceptación por suscripción y ciclo de cuota. */
public interface OverageConsentRepository {

    Optional<OverageConsent> find(TenantId tenantId, UUID subscriptionId, LocalDate windowStart);

    void save(OverageConsent consent);
}
