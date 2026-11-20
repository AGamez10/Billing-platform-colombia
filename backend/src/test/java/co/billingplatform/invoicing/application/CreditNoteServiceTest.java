package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.Correction;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceFixtures;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.TestTaxRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CreditNoteServiceTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Actor actor = Actor.user(UUID.randomUUID());
    private final Map<UUID, Invoice> stored = new HashMap<>();
    private final List<String> audit = new ArrayList<>();

    private final InvoiceRepository repo = new InvoiceRepository() {
        @Override public Optional<Invoice> findById(TenantId t, UUID id) {
            return t.equals(tenant) ? Optional.ofNullable(stored.get(id)) : Optional.empty();
        }
        @Override public Optional<Invoice> findByExternalReference(TenantId t, ExternalReference r) { return Optional.empty(); }
        @Override public void save(Invoice i) { stored.put(i.id(), i); }
        @Override public void deleteDraft(TenantId t, UUID id) { stored.remove(id); }
    };

    /** Cantidades acreditadas calculadas desde las notas guardadas que no fueron rechazadas, como la consulta real. */
    private final CreditNoteService.CreditedQuantities credited = (t, invoiceId) -> {
        Map<Integer, BigDecimal> out = new HashMap<>();
        for (Invoice n : stored.values()) {
            n.creditNote().filter(cn -> cn.creditedInvoiceId().equals(invoiceId)
                    && n.status() != InvoiceStatus.REJECTED && n.status() != InvoiceStatus.FAILED).ifPresent(cn -> {
                for (int i = 0; i < n.lines().size(); i++) {
                    out.merge(cn.lineOrigins().get(i), n.lines().get(i).quantity(), BigDecimal::add);
                }
            });
        }
        return out;
    };

    private CreditNoteService service() {
        return new CreditNoteService(repo, credited, TestTaxRates.catalog(), InvoiceFixtures.CALCULATOR, e -> audit.add(e.action()),
                Clock.fixed(InvoiceFixtures.NOW, ZoneOffset.UTC));
    }

    private Invoice acceptedInvoice() {
        Invoice i = InvoiceFixtures.finalized(tenant, InvoiceFixtures.account(tenant, ProviderEnvironment.HABILITATION));
        i.recordProviderResult(new SubmissionResult.Accepted(new ExternalReference("ext-1"),
                new ValidationEvidence("CUFE-ORIGINAL", ProviderEnvironment.HABILITATION, InvoiceFixtures.NOW)), InvoiceFixtures.NOW);
        stored.put(i.id(), i);
        return i;
    }

    @Test
    @DisplayName("anulación: copia todas las líneas, referencia número, CUFE y fecha, y queda en borrador")
    void annulment() {
        Invoice original = acceptedInvoice();
        Invoice note = service().create(tenant, actor, original.id(), Correction.Concept.ANNULMENT, "Factura con datos errados", List.of());
        assertEquals(DocumentKind.CREDIT_NOTE, note.kind());
        assertEquals(InvoiceStatus.DRAFT, note.status());
        assertEquals(original.calculation().payableAmount(), note.calculation().payableAmount());
        var cn = note.creditNote().orElseThrow();
        assertEquals("CUFE-ORIGINAL", cn.creditedDocumentKey());
        assertEquals(original.number().orElseThrow().formatted(), cn.creditedNumber());
        assertEquals(original.issueDate(), cn.creditedIssueDate());
        assertEquals(List.of(1, 2), cn.lineOrigins());
        assertEquals(original.customer(), note.customer());
        assertEquals(List.of("CREDIT_NOTE_DRAFT_CREATED"), audit);

        assertEquals("CREDIT_ALREADY_APPLIED", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.ANNULMENT, "otra vez", List.of())).code(), "no se anula dos veces");
        assertEquals("CREDIT_EXCEEDS_INVOICED", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "devolución", List.of(new CreditNoteService.ReturnLine(2, BigDecimal.ONE)))).code(),
                "tras anular no queda nada por devolver");
        assertEquals("CREDIT_NOTE_NOT_EDITABLE", assertThrows(DomainException.class, () -> note.replaceContent(note.customer(),
                note.lines(), InvoiceFixtures.TODAY, TestTaxRates.catalog(), InvoiceFixtures.CALCULATOR)).code());
    }

    @Test
    @DisplayName("devolución parcial: hasta lo que falte por acreditar de cada línea, contando borradores")
    void partialReturns() {
        Invoice original = acceptedInvoice();
        Invoice first = service().create(tenant, actor, original.id(), Correction.Concept.RETURN, "Devuelve una hora",
                List.of(new CreditNoteService.ReturnLine(1, BigDecimal.ONE)));
        assertEquals(new BigDecimal("178500.00"), first.calculation().payableAmount(), "150.000 + IVA 19 %");
        assertEquals(List.of(1), first.creditNote().orElseThrow().lineOrigins());

        assertEquals("CREDIT_EXCEEDS_INVOICED", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "de más", List.of(new CreditNoteService.ReturnLine(1, new BigDecimal("1.5"))))).code());
        service().create(tenant, actor, original.id(), Correction.Concept.RETURN, "El resto",
                List.of(new CreditNoteService.ReturnLine(1, BigDecimal.ONE), new CreditNoteService.ReturnLine(2, BigDecimal.ONE)));
        assertEquals("CREDIT_EXCEEDS_INVOICED", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "nada", List.of(new CreditNoteService.ReturnLine(2, new BigDecimal("0.1"))))).code());
        assertEquals("CREDIT_ALREADY_APPLIED", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.ANNULMENT, "anular", List.of())).code());
    }

    @Test
    @DisplayName("solo facturas validadas; líneas válidas y sin repetir; motivo obligatorio; aislado por organización")
    void guards() {
        Invoice draft = InvoiceFixtures.draft(tenant);
        stored.put(draft.id(), draft);
        assertEquals("INVOICE_NOT_CREDITABLE", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                draft.id(), Correction.Concept.ANNULMENT, "x y z", List.of())).code());

        Invoice original = acceptedInvoice();
        assertEquals("INVALID_CREDIT_NOTE", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "sin líneas", List.of())).code());
        assertEquals("INVALID_CREDIT_NOTE", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "línea 3", List.of(new CreditNoteService.ReturnLine(3, BigDecimal.ONE)))).code());
        assertEquals("INVALID_CREDIT_NOTE", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.RETURN, "repetida", List.of(new CreditNoteService.ReturnLine(1, BigDecimal.ONE),
                        new CreditNoteService.ReturnLine(1, BigDecimal.ONE)))).code());
        assertEquals("INVALID_CREDIT_NOTE", assertThrows(DomainException.class, () -> service().create(tenant, actor,
                original.id(), Correction.Concept.ANNULMENT, " ", List.of())).code());
        assertEquals("INVOICE_NOT_FOUND", assertThrows(DomainException.class, () -> service().create(new TenantId(UUID.randomUUID()),
                actor, original.id(), Correction.Concept.ANNULMENT, "ajena", List.of())).code());
    }

    @Test
    @DisplayName("la nota se emite con su propia numeración y lleva la corrección al documento electrónico")
    void finalizesWithCorrection() {
        Invoice original = acceptedInvoice();
        Invoice note = service().create(tenant, actor, original.id(), Correction.Concept.ANNULMENT, "Anulación", List.of());
        var account = InvoiceFixtures.account(tenant, ProviderEnvironment.HABILITATION);
        var range = co.billingplatform.invoicing.domain.NumberingRange.create(tenant, DocumentKind.CREDIT_NOTE,
                ProviderEnvironment.HABILITATION, "18760000009", "NC", 1, 1000,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2027, 12, 31));
        note.finalizeWith(range, account, InvoiceFixtures.TODAY, TestTaxRates.catalog(), InvoiceFixtures.CALCULATOR, InvoiceFixtures.NOW);
        assertEquals("NC1", note.number().orElseThrow().formatted());
        var doc = ElectronicDocumentMapper.toDocument(note, InvoiceFixtures.issuer());
        assertEquals(Correction.Concept.ANNULMENT, doc.correction().concept());
        assertEquals("CUFE-ORIGINAL", doc.correction().referencedDocumentKey());
    }
}
