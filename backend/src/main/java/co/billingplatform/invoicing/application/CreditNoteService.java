package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.Correction;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.invoicing.domain.CreditNoteDetails;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.Discount;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Notas crédito (ADR-0028): se arman desde la factura validada, nunca con montos libres.
 *
 * <ul>
 *   <li>{@link Correction.Concept#ANNULMENT}: acredita todo; solo si la factura no tiene otras
 *       notas vigentes.</li>
 *   <li>{@link Correction.Concept#RETURN}: devolución por líneas y cantidades, hasta lo que falte
 *       por acreditar de cada línea (contando borradores, para que dos notas simultáneas no
 *       acrediten dos veces lo mismo: la factura se bloquea mientras se calcula).</li>
 * </ul>
 * La nota queda en borrador y se emite como cualquier documento (numeración propia de notas).
 */
public final class CreditNoteService {

    public record ReturnLine(int lineNumber, BigDecimal quantity) {}

    public interface CreditedQuantities {
        /** Cantidad ya acreditada por línea de la factura, en notas que no fueron rechazadas ni fallaron. */
        Map<Integer, BigDecimal> creditedByLine(TenantId tenantId, UUID invoiceId);
    }

    private final InvoiceRepository invoices;
    private final CreditedQuantities credited;
    private final TaxCatalog taxes;
    private final DocumentCalculator calculator;
    private final AuditTrail audit;
    private final Clock clock;

    public CreditNoteService(InvoiceRepository invoices, CreditedQuantities credited, TaxCatalog taxes,
                             DocumentCalculator calculator, AuditTrail audit, Clock clock) {
        this.invoices = Objects.requireNonNull(invoices);
        this.credited = Objects.requireNonNull(credited);
        this.taxes = Objects.requireNonNull(taxes);
        this.calculator = Objects.requireNonNull(calculator);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Invoice create(TenantId tenantId, Actor actor, UUID invoiceId, Correction.Concept concept, String description,
                          List<ReturnLine> returns) {
        Objects.requireNonNull(concept, "concept is required");
        Invoice original = invoices.findById(tenantId, invoiceId) // bloquea la factura (FOR UPDATE)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        if (original.kind() != DocumentKind.INVOICE || original.status() != InvoiceStatus.ACCEPTED || original.evidence().isEmpty()) {
            throw new DomainException("INVOICE_NOT_CREDITABLE", "Only validated invoices can be corrected with a credit note");
        }
        Map<Integer, BigDecimal> already = credited.creditedByLine(tenantId, invoiceId);
        List<InvoiceLine> lines = new ArrayList<>();
        List<Integer> origins = new ArrayList<>();
        switch (concept) {
            case ANNULMENT -> {
                if (already.values().stream().anyMatch(q -> q.signum() > 0)) {
                    throw new DomainException("CREDIT_ALREADY_APPLIED",
                            "The invoice already has credit notes; return the remaining lines instead");
                }
                for (int i = 0; i < original.lines().size(); i++) {
                    lines.add(original.lines().get(i));
                    origins.add(i + 1);
                }
            }
            case RETURN -> {
                if (returns == null || returns.isEmpty()) {
                    throw new DomainException("INVALID_CREDIT_NOTE", "Choose at least one line to return");
                }
                Set<Integer> seen = new HashSet<>();
                for (ReturnLine r : returns) {
                    if (r.lineNumber() < 1 || r.lineNumber() > original.lines().size() || !seen.add(r.lineNumber())) {
                        throw new DomainException("INVALID_CREDIT_NOTE", "Invalid or repeated line " + r.lineNumber());
                    }
                    InvoiceLine source = original.lines().get(r.lineNumber() - 1);
                    BigDecimal remaining = source.quantity().subtract(already.getOrDefault(r.lineNumber(), BigDecimal.ZERO));
                    if (r.quantity() == null || r.quantity().signum() <= 0 || r.quantity().compareTo(remaining) > 0) {
                        throw new DomainException("CREDIT_EXCEEDS_INVOICED",
                                "Line %d: at most %s can be returned".formatted(r.lineNumber(), remaining.stripTrailingZeros().toPlainString()));
                    }
                    lines.add(returned(source, r.quantity()));
                    origins.add(r.lineNumber());
                }
            }
        }
        CreditNoteDetails details = new CreditNoteDetails(original.id(), concept, description,
                original.number().orElseThrow().formatted(), original.evidence().orElseThrow().documentKey(),
                original.issueDate(), origins);
        Invoice note = Invoice.creditNote(UUID.randomUUID(), original, details, lines, ColombianTime.today(clock), taxes,
                calculator, clock.instant());
        invoices.save(note);
        audit.record(new AuditTrail.Event(tenantId, actor, "CREDIT_NOTE_DRAFT_CREATED", "invoice", note.id().toString(),
                Map.of("creditedInvoice", invoiceId.toString(), "concept", concept.name(),
                        "amount", note.calculation().payableAmount().toPlainString())));
        return note;
    }

    /**
     * Línea devuelta: mismo producto, precio y tarifas. Un descuento porcentual se conserva; uno
     * por valor fijo solo se acredita completo (devolver toda la línea), para no repartir
     * centavos de forma ambigua.
     */
    private static InvoiceLine returned(InvoiceLine source, BigDecimal quantity) {
        Discount discount = source.discount();
        if (discount instanceof Discount.Amount a && a.value().signum() > 0 && quantity.compareTo(source.quantity()) != 0) {
            throw new DomainException("PARTIAL_RETURN_NOT_SUPPORTED",
                    "A line with a fixed-amount discount can only be returned in full");
        }
        return new InvoiceLine(source.productId(), source.description(), source.unitCode(), quantity, source.unitPrice(),
                discount, source.taxRateCodes());
    }
}
