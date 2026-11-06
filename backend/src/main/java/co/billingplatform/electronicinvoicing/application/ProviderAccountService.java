package co.billingplatform.electronicinvoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Configura con qué proveedor y en qué ambiente factura un tenant. Solo se acepta una
 * combinación que algún adaptador desplegado soporte (p. ej. el simulado solo en SIMULATION).
 *
 * <p>Las credenciales de proveedores reales no pasan por aquí todavía: se cargarán cifradas
 * cuando exista el primer adaptador real (docs/PROVIDERS.md).
 */
public final class ProviderAccountService {

    public record AccountState(ProviderAccount account, boolean active) {}

    private final ProviderAccountRepository accounts;
    private final ProviderRegistry registry;
    private final AuditTrail audit;

    public ProviderAccountService(ProviderAccountRepository accounts, ProviderRegistry registry, AuditTrail audit) {
        this.accounts = Objects.requireNonNull(accounts);
        this.registry = Objects.requireNonNull(registry);
        this.audit = Objects.requireNonNull(audit);
    }

    public ProviderAccount activate(TenantId tenantId, Actor actor, String providerId, ProviderEnvironment environment) {
        Objects.requireNonNull(environment, "environment is required");
        ProviderId id;
        try {
            id = new ProviderId(providerId == null ? "" : providerId.strip());
        } catch (IllegalArgumentException e) {
            throw new DomainException("PROVIDER_NOT_AVAILABLE", "Unknown provider");
        }
        ProviderAccount account = new ProviderAccount(UUID.randomUUID(), tenantId, id, environment, null, null);
        registry.providerFor(account, DocumentKind.INVOICE);   // valida proveedor, ambiente y capacidad
        accounts.activate(account);
        audit.record(new AuditTrail.Event(tenantId, actor, "PROVIDER_ACCOUNT_ACTIVATED", "provider_account",
                account.id().toString(), Map.of("provider", id.value(), "environment", environment.name())));
        return account;
    }

    public List<AccountState> list(TenantId tenantId) {
        Optional<UUID> active = accounts.findActive(tenantId).map(ProviderAccount::id);
        return accounts.list(tenantId).stream()
                .map(a -> new AccountState(a, active.map(a.id()::equals).orElse(false)))
                .toList();
    }
}
