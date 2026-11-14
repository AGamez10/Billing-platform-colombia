package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ElectronicDocument;
import co.billingplatform.invoicing.domain.DocumentNumber;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.tax.domain.DocumentCalculator;

import java.util.ArrayList;
import java.util.List;

/** Traduce una factura finalizada al modelo neutral que consumen los adaptadores. */
final class ElectronicDocumentMapper {

    private ElectronicDocumentMapper() {}

    static ElectronicDocument toDocument(Invoice invoice, Party issuer) {
        DocumentNumber number = invoice.number().orElseThrow(() ->
                new DomainException("INVALID_STATE_TRANSITION", "Only finalized invoices can be transmitted"));
        DocumentCalculator.Result calc = invoice.calculation();

        List<ElectronicDocument.Line> lines = new ArrayList<>();
        for (int i = 0; i < invoice.lines().size(); i++) {
            InvoiceLine source = invoice.lines().get(i);
            DocumentCalculator.CalculatedLine computed = calc.lines().get(i);
            lines.add(new ElectronicDocument.Line(
                    i + 1,
                    source.description(),
                    source.unitCode(),
                    source.quantity(),
                    source.unitPrice(),
                    computed.discountAmount(),
                    computed.taxableAmount(),
                    computed.taxes().stream().map(t -> new ElectronicDocument.LineTax(
                            t.rate().type().name(), t.rate().treatment().name(), t.rate().percentage(),
                            t.taxableAmount(), t.taxAmount())).toList()));
        }

        return new ElectronicDocument(
                invoice.id(),
                invoice.tenantId(),
                invoice.kind(),
                number.prefix(),
                number.number(),
                invoice.issueDate(),
                invoice.currency(),
                issuer,
                invoice.customer(),
                invoice.authorization().orElseThrow(),
                lines,
                calc.taxSubtotals().stream().map(s -> new ElectronicDocument.TaxSubtotal(
                        s.rate().type().name(), s.rate().treatment().name(), s.rate().percentage(),
                        s.taxableAmount(), s.taxAmount())).toList(),
                calc.lineExtensionAmount(),
                calc.taxTotal(),
                calc.payableAmount(),
                invoice.creditNote().map(cn -> cn.correction()).orElse(null));
    }
}
