package co.billingplatform.electronicinvoicing.domain;

import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Representación neutral (independiente de proveedor) de un documento listo para
 * transmitir. Es el único modelo que cruzan los adaptadores: cada adaptador lo traduce
 * al formato de su proveedor.
 */
public record ElectronicDocument(
        UUID documentId,
        TenantId tenantId,
        DocumentKind kind,
        String prefix,
        long number,
        LocalDate issueDate,
        String currency,
        Party issuer,
        Party customer,
        NumberingAuthorization authorization,
        List<Line> lines,
        List<TaxSubtotal> taxSubtotals,
        BigDecimal lineExtensionAmount,
        BigDecimal taxTotal,
        BigDecimal payableAmount) {

    public ElectronicDocument {
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(issueDate);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(customer);
        Objects.requireNonNull(authorization);
        lines = List.copyOf(lines);
        taxSubtotals = List.copyOf(taxSubtotals);
        Objects.requireNonNull(lineExtensionAmount);
        Objects.requireNonNull(taxTotal);
        Objects.requireNonNull(payableAmount);
    }

    public String fullNumber() {
        return prefix + number;
    }

    /** Datos de la resolución de numeración con la que se expidió el número. */
    public record NumberingAuthorization(
            String resolutionNumber, LocalDate validFrom, LocalDate validTo, long rangeFrom, long rangeTo) {}

    public record Line(
            int lineNumber,
            String description,
            String unitCode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            BigDecimal taxableAmount,
            List<LineTax> taxes) {
        public Line {
            taxes = List.copyOf(taxes);
        }
    }

    /** Tributo aplicado. {@code type}/{@code treatment} son nombres de los enums del motor tributario. */
    public record LineTax(String type, String treatment, BigDecimal percentage, BigDecimal taxableAmount, BigDecimal taxAmount) {}

    public record TaxSubtotal(String type, String treatment, BigDecimal percentage, BigDecimal taxableAmount, BigDecimal taxAmount) {}
}
