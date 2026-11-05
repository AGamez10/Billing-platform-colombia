package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.application.ProviderRegistry;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEvent;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;
import co.billingplatform.electronicinvoicing.port.ProcessedEventStore;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.domain.Invoice;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso: procesar un webhook de proveedor.
 *
 * <p>Orden de defensa: (1) la cuenta se resuelve por el token opaco de la URL, (2) el
 * proveedor de la URL debe coincidir con el de la cuenta, (3) el adaptador verifica la
 * firma, (4) el evento se descarta si ya se procesó, (5) la factura se busca dentro del
 * tenant de la cuenta, nunca por datos del cuerpo. Las respuestas a webhooks no
 * revelan si una factura existe.
 */
public final class ProviderWebhookService {

    public enum Outcome { APPLIED, DUPLICATE, UNKNOWN_DOCUMENT }

    private final ProviderAccountRepository accounts;
    private final ProviderRegistry registry;
    private final ProcessedEventStore processedEvents;
    private final InvoiceRepository invoices;
    private final Clock clock;

    public ProviderWebhookService(ProviderAccountRepository accounts, ProviderRegistry registry,
                                  ProcessedEventStore processedEvents, InvoiceRepository invoices, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.registry = Objects.requireNonNull(registry);
        this.processedEvents = Objects.requireNonNull(processedEvents);
        this.invoices = Objects.requireNonNull(invoices);
        this.clock = Objects.requireNonNull(clock);
    }

    /** @throws WebhookVerificationException si la cuenta no existe o la firma no es válida (la web responde 401 genérico) */
    public Outcome handle(ProviderId providerInPath, String webhookToken, InboundWebhook webhook) {
        ProviderAccount account = accounts.findByWebhookToken(webhookToken)
                .filter(a -> a.providerId().equals(providerInPath))
                .orElseThrow(() -> new WebhookVerificationException("unknown webhook endpoint"));

        ProviderEvent event = registry.providerFor(account, DocumentKind.INVOICE).parseWebhook(account, webhook);

        Optional<Invoice> invoice = invoices.findByExternalReference(account.tenantId(), event.reference());
        if (invoice.isEmpty()) {
            return Outcome.UNKNOWN_DOCUMENT;
        }
        if (!processedEvents.markIfNew(account.providerId(), event.eventId())) {
            return Outcome.DUPLICATE;
        }
        Invoice target = invoice.get();
        target.recordProviderResult(event.outcome(), clock.instant());
        invoices.save(target);
        return Outcome.APPLIED;
    }
}
