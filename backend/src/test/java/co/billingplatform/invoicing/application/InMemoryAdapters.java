package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ElectronicDocument;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.IdempotencyKey;
import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderCapabilities;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderEvent;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.port.ProcessedEventStore;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.shared.domain.TenantId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Adaptadores en memoria para probar casos de uso sin infraestructura (sin mocks de librería). */
final class InMemoryAdapters {

    private InMemoryAdapters() {}

    static final class Invoices implements InvoiceRepository {
        final Map<UUID, Invoice> store = new HashMap<>();
        int saves;

        @Override
        public Optional<Invoice> findById(TenantId tenantId, UUID invoiceId) {
            return Optional.ofNullable(store.get(invoiceId)).filter(i -> i.tenantId().equals(tenantId));
        }

        @Override
        public Optional<Invoice> findByExternalReference(TenantId tenantId, ExternalReference reference) {
            return store.values().stream()
                    .filter(i -> i.tenantId().equals(tenantId))
                    .filter(i -> i.externalReference().map(reference::equals).orElse(false))
                    .findFirst();
        }

        @Override
        public void save(Invoice invoice) {
            saves++;
            store.put(invoice.id(), invoice);
        }
    }

    static final class Accounts implements ProviderAccountRepository {
        final Map<UUID, ProviderAccount> byId = new HashMap<>();
        final Map<TenantId, UUID> active = new HashMap<>();
        final Map<String, UUID> webhookTokens = new HashMap<>();

        ProviderAccount activate(TenantId tenant, ProviderId provider, ProviderEnvironment env, String webhookToken) {
            ProviderAccount account = new ProviderAccount(UUID.randomUUID(), tenant, provider, env,
                    "secret://creds/" + tenant, "secret://webhook/" + tenant);
            byId.put(account.id(), account);
            active.put(tenant, account.id());
            if (webhookToken != null) {
                webhookTokens.put(webhookToken, account.id());
            }
            return account;
        }

        @Override
        public Optional<ProviderAccount> findActive(TenantId tenantId) {
            return Optional.ofNullable(active.get(tenantId)).map(byId::get);
        }

        @Override
        public Optional<ProviderAccount> findById(TenantId tenantId, UUID accountId) {
            return Optional.ofNullable(byId.get(accountId)).filter(a -> a.tenantId().equals(tenantId));
        }

        @Override
        public Optional<ProviderAccount> findByWebhookToken(String webhookToken) {
            return Optional.ofNullable(webhookTokens.get(webhookToken)).map(byId::get);
        }
    }

    static final class ProcessedEvents implements ProcessedEventStore {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean markIfNew(ProviderId providerId, String eventId) {
            return seen.add(providerId + ":" + eventId);
        }
    }

    /** Proveedor guionado con capacidades configurables, para probar la lógica de enrutamiento y reconciliación. */
    static final class ScriptedProvider implements ElectronicInvoicingProvider {
        final ProviderId id;
        final ProviderCapabilities capabilities;
        final Deque<SubmissionResult> submitResults = new ArrayDeque<>();
        final Deque<Optional<SubmissionResult>> lookupResults = new ArrayDeque<>();
        final List<ElectronicDocument> submitted = new ArrayList<>();
        int queryCalls;
        int lookupCalls;
        RuntimeException throwOnSubmit;

        ScriptedProvider(String id, boolean idempotent, boolean lookup, Set<DocumentKind> kinds) {
            this.id = new ProviderId(id);
            this.capabilities = new ProviderCapabilities(kinds, Set.of(ProviderEnvironment.HABILITATION),
                    idempotent, true, lookup, false);
        }

        @Override public ProviderId id() { return id; }

        @Override public ProviderCapabilities capabilities() { return capabilities; }

        @Override
        public SubmissionResult submit(ProviderAccount account, ElectronicDocument document, IdempotencyKey key) {
            if (throwOnSubmit != null) {
                throw throwOnSubmit;
            }
            submitted.add(document);
            return submitResults.isEmpty()
                    ? new SubmissionResult.Pending(new ExternalReference(id + "-" + document.fullNumber()))
                    : submitResults.poll();
        }

        @Override
        public SubmissionResult queryStatus(ProviderAccount account, ExternalReference reference) {
            queryCalls++;
            return new SubmissionResult.Pending(reference);
        }

        @Override
        public Optional<SubmissionResult> findByDocumentNumber(ProviderAccount account, String fullDocumentNumber) {
            lookupCalls++;
            return lookupResults.isEmpty() ? Optional.empty() : lookupResults.poll();
        }

        @Override
        public ProviderEvent parseWebhook(ProviderAccount account, InboundWebhook webhook) {
            throw new UnsupportedOperationException();
        }
    }
}
