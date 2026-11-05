package co.billingplatform.electronicinvoicing.domain;

import co.billingplatform.shared.domain.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Configuración de un tenant con un proveedor: qué adaptador, en qué ambiente y con
 * qué credenciales. Las credenciales nunca viajan en este objeto: solo referencias a un
 * almacén de secretos ({@code SecretResolver}), para que no terminen en logs ni en DTOs.
 *
 * <p>Cada tenant tiene su propia cuenta: no se comparten credenciales entre
 * organizaciones salvo que el contrato con el proveedor lo permita explícitamente.
 */
public record ProviderAccount(
        UUID id,
        TenantId tenantId,
        ProviderId providerId,
        ProviderEnvironment environment,
        String credentialsSecretRef,
        String webhookSecretRef) {

    public ProviderAccount {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(environment);
    }

    @Override
    public String toString() {
        // Deliberadamente sin referencias de secretos.
        return "ProviderAccount[id=%s, tenant=%s, provider=%s, env=%s]".formatted(id, tenantId, providerId, environment);
    }
}
