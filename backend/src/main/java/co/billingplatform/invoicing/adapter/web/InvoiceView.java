package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.domain.DocumentNumber;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.tax.domain.Discount;
import co.billingplatform.tax.domain.DocumentCalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Representación pública de una factura.
 *
 * <p>{@code legallyValid} solo es verdadero con validación en PRODUCTION; {@code simulated}
 * marca todo lo emitido con el proveedor simulado, que no tiene efecto tributario.
 */
record InvoiceView(
        UUID id,
        String kind,
        String status,
        String number,
        LocalDate issueDate,
        String environment,
        boolean simulated,
        boolean legallyValid,
        Customer customer,
        List<Line> lines,
        List<TaxSubtotal> taxSubtotals,
        Totals totals,
        String documentKey,
        Instant validatedAt,
        String validationEnvironment,
        List<String> rejectionReasons,
        boolean outcomeUncertain,
        String lastError,
        List<HistoryEntry> history) {

    record Customer(String identificationType, String identificationNumber, Integer checkDigit, String legalName,
                    String email, boolean finalConsumer) {}

    record Line(int lineNumber, String description, String unitCode, BigDecimal quantity, BigDecimal unitPrice,
                String discountKind, BigDecimal discountValue, List<String> taxRateCodes, BigDecimal grossAmount,
                BigDecimal discountAmount, BigDecimal taxableAmount, BigDecimal taxTotal, BigDecimal lineTotal) {}

    record TaxSubtotal(String code, String type, BigDecimal percentage, BigDecimal taxableAmount, BigDecimal taxAmount) {}

    record Totals(String currency, BigDecimal lineExtensionAmount, BigDecimal taxTotal, BigDecimal payableAmount) {}

    record HistoryEntry(String from, String to, Instant at, String detail) {}

    static InvoiceView of(Invoice i) {
        DocumentCalculator.Result calc = i.calculation();
        List<Line> lines = new ArrayList<>();
        for (int n = 0; n < i.lines().size(); n++) {
            InvoiceLine l = i.lines().get(n);
            DocumentCalculator.CalculatedLine c = calc.lines().get(n);
            String kind;
            BigDecimal value;
            switch (l.discount()) {
                case Discount.Percentage p -> { kind = "PERCENT"; value = p.percent(); }
                case Discount.Amount a -> { kind = a.value().signum() == 0 ? null : "AMOUNT"; value = a.value().signum() == 0 ? null : a.value(); }
            }
            lines.add(new Line(n + 1, l.description(), l.unitCode(), l.quantity(), l.unitPrice(), kind, value,
                    l.taxRateCodes(), c.grossAmount(), c.discountAmount(), c.taxableAmount(), c.taxTotal(), c.lineTotal()));
        }
        var id = i.customer().identification();
        return new InvoiceView(
                i.id(),
                i.kind().name(),
                i.status().name(),
                i.number().map(DocumentNumber::formatted).orElse(null),
                i.issueDate(),
                i.environment().map(Enum::name).orElse(null),
                i.environment().map(e -> e == ProviderEnvironment.SIMULATION).orElse(false),
                i.isLegallyValid(),
                new Customer(id.type().name(), id.number(), id.checkDigit(), i.customer().legalName(), i.customer().email(),
                        i.customer().isFinalConsumer()),
                lines,
                calc.taxSubtotals().stream().map(t -> new TaxSubtotal(t.rate().code(), t.rate().type().name(),
                        t.rate().percentage(), t.taxableAmount(), t.taxAmount())).toList(),
                new Totals(i.currency(), calc.lineExtensionAmount(), calc.taxTotal(), calc.payableAmount()),
                i.evidence().map(e -> e.documentKey()).orElse(null),
                i.evidence().map(e -> e.validatedAt()).orElse(null),
                i.evidence().map(e -> e.environment().name()).orElse(null),
                i.rejectionReasons(),
                i.outcomeUncertain(),
                i.lastError().orElse(null),
                i.history().stream().map(h -> new HistoryEntry(h.from() == null ? null : h.from().name(),
                        h.to().name(), h.at(), h.detail())).toList());
    }
}
