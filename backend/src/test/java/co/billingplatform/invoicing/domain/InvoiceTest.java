package co.billingplatform.invoicing.domain;

import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.TaxCatalog;
import co.billingplatform.tax.domain.TaxRate;
import co.billingplatform.tax.domain.TaxTreatment;
import co.billingplatform.tax.domain.TaxType;
import co.billingplatform.tax.domain.TestTaxRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static co.billingplatform.invoicing.domain.InvoiceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ExternalReference ref = new ExternalReference("ext-1");

    private static ValidationEvidence evidence(ProviderEnvironment env) {
        return new ValidationEvidence("CUFE-ABC", env, NOW);
    }

    @Test
    @DisplayName("un borrador calcula totales y no consume numeración")
    void draftComputesTotals() {
        Invoice invoice = draft(tenant);
        assertEquals(InvoiceStatus.DRAFT, invoice.status());
        assertEquals(new BigDecimal("397000.00"), invoice.calculation().payableAmount()); // 300000 + 57000 + 40000
        assertTrue(invoice.number().isEmpty());
    }

    @Test
    @DisplayName("finalizar asigna número, fija contenido y pasa a pendiente de transmisión")
    void finalizeAssignsNumber() {
        Invoice invoice = finalized(tenant);
        assertEquals(InvoiceStatus.PENDING_SUBMISSION, invoice.status());
        assertEquals("SETP990000000", invoice.number().orElseThrow().formatted());
        assertTrue(invoice.authorization().isPresent());
        assertEquals("INVALID_STATE_TRANSITION", assertThrows(DomainException.class, () ->
                invoice.replaceContent(customer(), lines(), TODAY, TestTaxRates.catalog(), CALCULATOR)).code());
    }

    @Test
    @DisplayName("al finalizar recalcula con la tarifa vigente en la fecha de emisión")
    void finalizeRecalculatesWithIssueDateRate() {
        TaxCatalog catalog = TaxCatalog.of(List.of(
                new TaxRate("TEST_GENERAL", TaxType.IVA, TaxTreatment.TAXED, new BigDecimal("10"),
                        LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), "test fixture"),
                new TaxRate("TEST_GENERAL", TaxType.IVA, TaxTreatment.TAXED, new BigDecimal("12"),
                        LocalDate.of(2027, 1, 1), null, "test fixture")));
        List<InvoiceLine> lines = List.of(new InvoiceLine(null, "Item", "94", BigDecimal.ONE,
                new BigDecimal("1000"), null, List.of("TEST_GENERAL")));

        Invoice invoice = Invoice.draft(UUID.randomUUID(), tenant, customer(), lines,
                LocalDate.of(2026, 12, 31), catalog, CALCULATOR, NOW);
        assertEquals(new BigDecimal("100.00"), invoice.calculation().taxTotal());

        invoice.finalizeWith(range(tenant), LocalDate.of(2027, 1, 1), catalog, CALCULATOR, NOW);
        assertEquals(new BigDecimal("120.00"), invoice.calculation().taxTotal());
        assertEquals(LocalDate.of(2027, 1, 1), invoice.issueDate());
    }

    @Test
    @DisplayName("no se puede numerar con la resolución de otra organización")
    void rejectsForeignNumberingRange() {
        Invoice invoice = draft(tenant);
        NumberingRange foreign = range(new TenantId(UUID.randomUUID()));
        assertEquals("NUMBERING_RANGE_NOT_FOUND", assertThrows(DomainException.class, () ->
                invoice.finalizeWith(foreign, TODAY, TestTaxRates.catalog(), CALCULATOR, NOW)).code());
        assertEquals(InvoiceStatus.DRAFT, invoice.status());
    }

    @Test
    @DisplayName("no se finaliza una factura con total cero")
    void rejectsZeroTotal() {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), tenant, customer(),
                List.of(new InvoiceLine(null, "Gratis", "94", BigDecimal.ONE, BigDecimal.ZERO, null, List.of())),
                TODAY, TestTaxRates.catalog(), CALCULATOR, NOW);
        NumberingRange range = range(tenant);
        assertEquals("INVOICE_TOTAL_NOT_POSITIVE", assertThrows(DomainException.class, () ->
                invoice.finalizeWith(range, TODAY, TestTaxRates.catalog(), CALCULATOR, NOW)).code());
        assertEquals(990000000, range.nextNumber(), "no consume número");
    }

    @Test
    @DisplayName("validada en simulación o habilitación NO tiene validez tributaria")
    void legalValidityRequiresProduction() {
        Invoice sim = finalized(tenant);
        sim.recordProviderResult(new SubmissionResult.Accepted(ref, evidence(ProviderEnvironment.SIMULATION)), NOW);
        assertEquals(InvoiceStatus.ACCEPTED, sim.status());
        assertFalse(sim.isLegallyValid());

        Invoice hab = finalized(tenant);
        hab.recordProviderResult(new SubmissionResult.Accepted(ref, evidence(ProviderEnvironment.HABILITATION)), NOW);
        assertFalse(hab.isLegallyValid());

        Invoice prod = finalized(tenant);
        prod.recordProviderResult(new SubmissionResult.Accepted(ref, evidence(ProviderEnvironment.PRODUCTION)), NOW);
        assertTrue(prod.isLegallyValid());
    }

    @Test
    @DisplayName("no hay validación sin evidencia")
    void acceptedRequiresEvidence() {
        assertThrows(NullPointerException.class, () -> new SubmissionResult.Accepted(ref, null));
        assertThrows(IllegalArgumentException.class, () -> new ValidationEvidence(" ", ProviderEnvironment.PRODUCTION, NOW));
    }

    @Test
    @DisplayName("resultados repetidos son idempotentes; resultados contradictorios se rechazan")
    void duplicateAndConflictingResults() {
        Invoice invoice = finalized(tenant);
        var accepted = new SubmissionResult.Accepted(ref, evidence(ProviderEnvironment.SIMULATION));
        invoice.recordProviderResult(accepted, NOW);
        int historySize = invoice.history().size();

        assertDoesNotThrow(() -> invoice.recordProviderResult(accepted, NOW));
        assertEquals(historySize, invoice.history().size());

        assertEquals("CONFLICTING_PROVIDER_RESULT", assertThrows(DomainException.class, () ->
                invoice.recordProviderResult(new SubmissionResult.Rejected(ref, List.of("x")), NOW)).code());
        assertEquals(InvoiceStatus.ACCEPTED, invoice.status());
    }

    @Test
    @DisplayName("un timeout tras posible recepción deja el resultado como incierto")
    void uncertainOutcome() {
        Invoice invoice = finalized(tenant);
        invoice.recordProviderResult(new SubmissionResult.TransientFailure("connection refused", false), NOW);
        assertEquals(InvoiceStatus.PENDING_SUBMISSION, invoice.status());
        assertFalse(invoice.outcomeUncertain());

        invoice.recordProviderResult(new SubmissionResult.TransientFailure("read timeout", true), NOW);
        assertEquals(InvoiceStatus.SUBMITTED, invoice.status());
        assertTrue(invoice.outcomeUncertain());

        invoice.recordProviderResult(new SubmissionResult.Pending(ref), NOW);
        assertFalse(invoice.outcomeUncertain());
        assertEquals(ref, invoice.externalReference().orElseThrow());
    }

    @Test
    @DisplayName("una factura enviada queda ligada a su cuenta de proveedor")
    void bindsProviderAccount() {
        Invoice invoice = finalized(tenant);
        UUID account = UUID.randomUUID();
        invoice.bindProviderAccount(account);
        assertDoesNotThrow(() -> invoice.bindProviderAccount(account));
        assertEquals("PROVIDER_ACCOUNT_MISMATCH", assertThrows(DomainException.class,
                () -> invoice.bindProviderAccount(UUID.randomUUID())).code());
    }

    @Test
    @DisplayName("el historial registra cada transición")
    void recordsHistory() {
        Invoice invoice = finalized(tenant);
        invoice.recordProviderResult(new SubmissionResult.Rejected(ref, List.of("Simulated validation rule")), NOW);
        var transitions = invoice.history().stream().map(h -> h.to()).toList();
        assertEquals(List.of(InvoiceStatus.DRAFT, InvoiceStatus.PENDING_SUBMISSION, InvoiceStatus.REJECTED), transitions);
        assertEquals(List.of("Simulated validation rule"), invoice.rejectionReasons());
    }
}
