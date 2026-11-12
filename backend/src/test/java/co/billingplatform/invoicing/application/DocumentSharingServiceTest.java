package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceFixtures;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSharingServiceTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID user = UUID.randomUUID();
    private final List<DocumentSharingService.ShareLink> stored = new ArrayList<>();
    private final List<Mailer.Email> sent = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private Invoice invoice;

    private final DocumentSharingService.ShareLinks links = new DocumentSharingService.ShareLinks() {
        @Override public void insert(DocumentSharingService.ShareLink l) { stored.add(l); }
        @Override public int countSince(TenantId t, UUID id, Instant since) {
            return (int) stored.stream().filter(l -> l.invoiceId().equals(id) && !l.createdAt().isBefore(since)).count();
        }
        @Override public Optional<DocumentSharingService.Target> find(byte[] hash) {
            return stored.stream().filter(l -> java.util.Arrays.equals(l.tokenHash(), hash)).findFirst()
                    .map(l -> new DocumentSharingService.Target(l.tenantId(), l.invoiceId()));
        }
    };

    private final InvoiceRepository repo = new InvoiceRepository() {
        @Override public Optional<Invoice> findById(TenantId t, UUID id) {
            return invoice != null && t.equals(tenant) && invoice.id().equals(id) ? Optional.of(invoice) : Optional.empty();
        }
        @Override public Optional<Invoice> findByExternalReference(TenantId t, ExternalReference r) { return Optional.empty(); }
        @Override public void save(Invoice i) { throw new UnsupportedOperationException(); }
        @Override public void deleteDraft(TenantId t, UUID id) { throw new UnsupportedOperationException(); }
    };

    private DocumentSharingService service() {
        return new DocumentSharingService(repo, links, t -> InvoiceFixtures.issuer(), sent::add, e -> audit.add(e.action()),
                "Marca", "https://app.example.co/", Clock.fixed(InvoiceFixtures.NOW, ZoneOffset.UTC));
    }

    private Invoice accepted(ProviderEnvironment env) {
        Invoice i = InvoiceFixtures.finalized(tenant, InvoiceFixtures.account(tenant, env));
        i.recordProviderResult(new SubmissionResult.Accepted(new ExternalReference("ext-1"),
                new ValidationEvidence("CUFE-1", env, InvoiceFixtures.NOW, "QR")), InvoiceFixtures.NOW);
        return i;
    }

    @Test
    @DisplayName("solo documentos validados; el enlace lleva 256 bits en el fragmento y se guarda solo su hash")
    void linkOnlyForValidated() {
        DocumentSharingService s = service();
        invoice = InvoiceFixtures.finalized(tenant);
        assertEquals("INVOICE_NOT_VALIDATED", assertThrows(DomainException.class,
                () -> s.createLink(tenant, user, invoice.id())).code());

        invoice = accepted(ProviderEnvironment.PRODUCTION);
        DocumentSharingService.Shared shared = s.createLink(tenant, user, invoice.id());
        assertTrue(shared.link().matches("https://app\\.example\\.co/documento#t=[A-Za-z0-9_-]{43}"), shared.link());
        String token = shared.link().substring(shared.link().indexOf("#t=") + 3);
        assertTrue(java.util.Arrays.equals(OpaqueTokens.sha256(token), stored.getFirst().tokenHash()), "se guarda solo el hash");
        assertEquals(Optional.of(new DocumentSharingService.Target(tenant, invoice.id())), s.resolve(token));
        assertEquals(Optional.empty(), s.resolve("corto"));
        assertEquals(Optional.empty(), s.resolve(token.substring(1) + "x"));
        assertEquals(List.of("INVOICE_LINK_CREATED"), audit);
        assertEquals("INVOICE_NOT_FOUND", assertThrows(DomainException.class,
                () -> s.createLink(new TenantId(UUID.randomUUID()), user, invoice.id())).code());
    }

    @Test
    @DisplayName("correo: al comprador o a otro correo válido; asunto marca lo que no tiene validez tributaria")
    void email() {
        DocumentSharingService s = service();
        invoice = accepted(ProviderEnvironment.HABILITATION);
        s.sendByEmail(tenant, user, invoice.id(), null);
        Mailer.Email mail = sent.getLast();
        assertEquals("cliente@example.com", mail.to());
        assertTrue(mail.subject().startsWith("[Sin validez tributaria] Factura electrónica SETP990000000 de Emisor de Prueba"), mail.subject());
        assertTrue(mail.text().contains("habilitación"));

        s.sendByEmail(tenant, user, invoice.id(), " Contador@Example.com ");
        assertEquals("contador@example.com", sent.getLast().to());
        assertThrows(DomainException.class, () -> s.sendByEmail(tenant, user, invoice.id(), "no-es-correo"));

        invoice = accepted(ProviderEnvironment.PRODUCTION);
        s.sendByEmail(tenant, user, invoice.id(), null);
        assertTrue(sent.getLast().subject().startsWith("Factura electrónica"), "en producción no lleva advertencia");
        assertFalse(sent.getLast().text().contains("validez"));
    }

    @Test
    @DisplayName("límite diario de enlaces por documento")
    void dailyLimit() {
        DocumentSharingService s = service();
        invoice = accepted(ProviderEnvironment.PRODUCTION);
        for (int i = 0; i < DocumentSharingService.MAX_LINKS_PER_DAY; i++) {
            s.createLink(tenant, user, invoice.id());
        }
        assertEquals("SHARE_LIMIT_REACHED", assertThrows(DomainException.class,
                () -> s.createLink(tenant, user, invoice.id())).code());
    }

    @Test
    @DisplayName("valores en pesos colombianos")
    void money() {
        assertEquals("$ 397.000", DocumentSharingService.cop(new java.math.BigDecimal("397000.00")));
        assertEquals("$ 1.234,50", DocumentSharingService.cop(new java.math.BigDecimal("1234.50")));
    }
}
