package co.billingplatform.electronicinvoicing.port;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.shared.domain.TenantId;

import java.util.Optional;
import java.util.UUID;

/** Cuentas de proveedor por tenant. Toda consulta va acotada por tenant, salvo la de webhooks. */
public interface ProviderAccountRepository {

    /** Cuenta activa del tenant: la que se usa para nuevos envíos. Una sola por tenant. */
    Optional<ProviderAccount> findActive(TenantId tenantId);

    /** Cuenta específica del tenant (activa o no), para continuar envíos ya iniciados. */
    Optional<ProviderAccount> findById(TenantId tenantId, UUID accountId);

    /**
     * Resuelve la cuenta por el token opaco incluido en la URL de webhook de esa cuenta.
     * Es la única búsqueda sin tenant: el tenant se deduce de la cuenta, nunca del cuerpo
     * del webhook, y el webhook solo se acepta si luego pasa la verificación de firma.
     */
    Optional<ProviderAccount> findByWebhookToken(String webhookToken);
}
