package co.billingplatform.electronicinvoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.shared.domain.DomainException;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enruta cada operación al adaptador configurado en la cuenta del tenant y verifica
 * que el adaptador soporte lo que se le pide. Cambiar de proveedor es cambiar la
 * {@link ProviderAccount} del tenant (configuración), no el código del núcleo.
 */
public final class ProviderRegistry {

    private final Map<ProviderId, ElectronicInvoicingProvider> providers;

    public ProviderRegistry(Collection<? extends ElectronicInvoicingProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                ElectronicInvoicingProvider::id,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Duplicate provider id: " + a.id());
                }));
    }

    public ElectronicInvoicingProvider providerFor(ProviderAccount account, DocumentKind kind) {
        ElectronicInvoicingProvider provider = providers.get(account.providerId());
        if (provider == null) {
            throw new DomainException("PROVIDER_NOT_AVAILABLE",
                    "Provider '%s' is not available in this deployment".formatted(account.providerId()));
        }
        if (!provider.capabilities().supports(account.environment())) {
            throw new DomainException("PROVIDER_ENVIRONMENT_UNSUPPORTED",
                    "Provider '%s' does not operate in %s".formatted(account.providerId(), account.environment()));
        }
        if (!provider.capabilities().supports(kind)) {
            throw new DomainException("PROVIDER_CAPABILITY_UNSUPPORTED",
                    "Provider '%s' does not support %s".formatted(account.providerId(), kind));
        }
        return provider;
    }
}
