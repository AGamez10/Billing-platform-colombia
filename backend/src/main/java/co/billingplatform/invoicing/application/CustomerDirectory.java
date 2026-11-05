package co.billingplatform.invoicing.application;

import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;

import java.util.Optional;
import java.util.UUID;

/** Datos actuales de un cliente activo del tenant, para tomar el snapshot del documento. */
public interface CustomerDirectory {

    Optional<Party> findActive(TenantId tenantId, UUID customerId);
}
