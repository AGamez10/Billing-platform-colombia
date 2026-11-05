package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.adapter.common.HmacWebhookVerifier;
import co.billingplatform.electronicinvoicing.adapter.mock.MockElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.application.ProviderRegistry;
import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;
import co.billingplatform.invoicing.application.InMemoryAdapters.Accounts;
import co.billingplatform.invoicing.application.InMemoryAdapters.Invoices;
import co.billingplatform.invoicing.application.InMemoryAdapters.ProcessedEvents;
import co.billingplatform.invoicing.application.ProviderWebhookService.Outcome;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceFixtures;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProviderWebhookServiceTest {

    private static final Clock CLOCK = Clock.fixed(InvoiceFixtures.NOW, ZoneOffset.UTC);
    private static final byte[] SECRET_A = filled((byte) 'a');
    private static final byte[] SECRET_B = filled((byte) 'b');

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final Invoices invoices = new Invoices();
    private final Accounts accounts = new Accounts();
    private final MockElectronicInvoicingProvider mock = new MockElectronicInvoicingProvider(
            ref -> ref.endsWith(tenantA.toString()) ? SECRET_A : SECRET_B, CLOCK,
            doc -> MockElectronicInvoicingProvider.Behavior.PENDING);
    private final ProviderRegistry registry = new ProviderRegistry(List.of(mock));
    private final ProviderWebhookService webhooks =
            new ProviderWebhookService(accounts, registry, new ProcessedEvents(), invoices, CLOCK);

    private static byte[] filled(byte b) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, b);
        return bytes;
    }

    /** Factura del tenant A enviada al mock y pendiente de resultado. */
    private Invoice pendingInvoiceOfA() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, "tok-a");
        accounts.activate(tenantB, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, "tok-b");
        Invoice invoice = InvoiceFixtures.finalized(tenantA);
        invoices.save(invoice);
        new SubmitInvoiceService(invoices, accounts, registry, t -> InvoiceFixtures.issuer(), CLOCK, 3)
                .submit(tenantA, invoice.id());
        assertEquals(InvoiceStatus.SUBMITTED, invoice.status());
        return invoice;
    }

    private static InboundWebhook signed(byte[] secret, Instant sentAt, String body) {
        String ts = Long.toString(sentAt.getEpochSecond());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = HexFormat.of().formatHex(HmacWebhookVerifier.sign(secret, ts, bytes));
        return new InboundWebhook(Map.of("X-Mock-Timestamp", List.of(ts), "X-Mock-Signature", List.of(signature)),
                bytes, InvoiceFixtures.NOW);
    }

    private static String acceptedBody(String eventId, Invoice invoice) {
        return "eventId=" + eventId + "\nreference=" + invoice.externalReference().orElseThrow().value()
                + "\noutcome=ACCEPTED\ndocumentKey=abc";
    }

    @Test
    @DisplayName("webhook firmado válido actualiza la factura; el mismo evento repetido se ignora")
    void appliesSignedWebhookOnce() {
        Invoice invoice = pendingInvoiceOfA();
        InboundWebhook webhook = signed(SECRET_A, InvoiceFixtures.NOW, acceptedBody("evt-1", invoice));

        assertEquals(Outcome.APPLIED, webhooks.handle(MockElectronicInvoicingProvider.ID, "tok-a", webhook));
        assertEquals(InvoiceStatus.ACCEPTED, invoice.status());
        assertFalse(invoice.isLegallyValid());

        int saves = invoices.saves;
        assertEquals(Outcome.DUPLICATE, webhooks.handle(MockElectronicInvoicingProvider.ID, "tok-a", webhook));
        assertEquals(saves, invoices.saves);
    }

    @Test
    @DisplayName("webhook con firma inválida, cuerpo alterado o fuera de ventana se rechaza")
    void rejectsForgedWebhooks() {
        Invoice invoice = pendingInvoiceOfA();
        ProviderId mockId = MockElectronicInvoicingProvider.ID;

        InboundWebhook wrongSecret = signed(SECRET_B, InvoiceFixtures.NOW, acceptedBody("evt-2", invoice));
        assertThrows(WebhookVerificationException.class, () -> webhooks.handle(mockId, "tok-a", wrongSecret));

        InboundWebhook valid = signed(SECRET_A, InvoiceFixtures.NOW, acceptedBody("evt-3", invoice));
        InboundWebhook tampered = new InboundWebhook(
                Map.of("X-Mock-Timestamp", List.of(valid.header("X-Mock-Timestamp").orElseThrow()),
                        "X-Mock-Signature", List.of(valid.header("X-Mock-Signature").orElseThrow())),
                acceptedBody("evt-3", invoice).replace("ACCEPTED", "REJECTED").getBytes(StandardCharsets.UTF_8),
                InvoiceFixtures.NOW);
        assertThrows(WebhookVerificationException.class, () -> webhooks.handle(mockId, "tok-a", tampered));

        InboundWebhook stale = signed(SECRET_A, InvoiceFixtures.NOW.minusSeconds(600), acceptedBody("evt-4", invoice));
        assertThrows(WebhookVerificationException.class, () -> webhooks.handle(mockId, "tok-a", stale));

        assertThrows(WebhookVerificationException.class, () -> webhooks.handle(mockId, "unknown-token", valid));
        assertThrows(WebhookVerificationException.class, () -> webhooks.handle(new ProviderId("kiai"), "tok-a", valid));

        assertEquals(InvoiceStatus.SUBMITTED, invoice.status(), "ningún webhook inválido cambió el estado");
    }

    @Test
    @DisplayName("aislamiento: un webhook de la cuenta de B no puede afectar facturas de A")
    void webhookCannotCrossTenants() {
        Invoice invoiceOfA = pendingInvoiceOfA();
        InboundWebhook fromB = signed(SECRET_B, InvoiceFixtures.NOW, acceptedBody("evt-5", invoiceOfA));

        assertEquals(Outcome.UNKNOWN_DOCUMENT, webhooks.handle(MockElectronicInvoicingProvider.ID, "tok-b", fromB));
        assertEquals(InvoiceStatus.SUBMITTED, invoiceOfA.status());
    }
}
