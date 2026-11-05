package co.billingplatform.tax.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.RoundingPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Motor de cálculo de documentos (facturas y notas). Puro, determinista y sin estado.
 *
 * <p>Algoritmo (política documentada en ADR-0005):
 * <ol>
 *   <li>bruto = cantidad × precio unitario (sin redondear)</li>
 *   <li>base gravable de línea = redondear(bruto − descuento)</li>
 *   <li>impuesto de línea = redondear(base × tarifa) por cada tributo de la línea</li>
 *   <li>subtotales por tarifa = suma de bases y de impuestos de línea ya redondeados</li>
 *   <li>total a pagar = Σ bases + Σ impuestos</li>
 * </ol>
 * Al sumar valores ya redondeados, los totales cuadran exactamente con el detalle,
 * que es lo que un validador de consistencia verifica.
 *
 * <p>Fuera de alcance de esta versión (ver docs/ROADMAP.md): retenciones,
 * cargos/descuentos globales, anticipos, impuestos de valor fijo por unidad.
 */
public final class DocumentCalculator {

    private final RoundingPolicy rounding;

    public DocumentCalculator(RoundingPolicy rounding) {
        this.rounding = Objects.requireNonNull(rounding);
    }

    public record LineInput(BigDecimal quantity, BigDecimal unitPrice, Discount discount, List<TaxRate> taxes) {
        public LineInput {
            Objects.requireNonNull(quantity);
            Objects.requireNonNull(unitPrice);
            discount = discount == null ? Discount.NONE : discount;
            taxes = List.copyOf(taxes == null ? List.of() : taxes);
        }
    }

    public record LineTax(TaxRate rate, BigDecimal taxableAmount, BigDecimal taxAmount) {}

    public record CalculatedLine(
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal taxableAmount,
            List<LineTax> taxes,
            BigDecimal taxTotal,
            BigDecimal lineTotal) {}

    public record TaxSubtotal(TaxRate rate, BigDecimal taxableAmount, BigDecimal taxAmount) {}

    public record Result(
            List<CalculatedLine> lines,
            List<TaxSubtotal> taxSubtotals,
            BigDecimal lineExtensionAmount,
            BigDecimal taxTotal,
            BigDecimal payableAmount) {}

    public Result calculate(List<LineInput> inputs) {
        Objects.requireNonNull(inputs);
        if (inputs.isEmpty()) {
            throw new DomainException("DOCUMENT_WITHOUT_LINES", "A document needs at least one line");
        }

        List<CalculatedLine> lines = new ArrayList<>(inputs.size());
        Map<String, BigDecimal[]> subtotalsByRate = new LinkedHashMap<>();
        Map<String, TaxRate> ratesByCode = new LinkedHashMap<>();
        BigDecimal lineExtension = rounding.zero();
        BigDecimal taxTotal = rounding.zero();

        for (int i = 0; i < inputs.size(); i++) {
            CalculatedLine line = calculateLine(i + 1, inputs.get(i));
            lines.add(line);
            lineExtension = lineExtension.add(line.taxableAmount());
            taxTotal = taxTotal.add(line.taxTotal());
            for (LineTax tax : line.taxes()) {
                ratesByCode.putIfAbsent(tax.rate().code(), tax.rate());
                BigDecimal[] acc = subtotalsByRate.computeIfAbsent(
                        tax.rate().code(), k -> new BigDecimal[] {rounding.zero(), rounding.zero()});
                acc[0] = acc[0].add(tax.taxableAmount());
                acc[1] = acc[1].add(tax.taxAmount());
            }
        }

        List<TaxSubtotal> subtotals = subtotalsByRate.entrySet().stream()
                .map(e -> new TaxSubtotal(ratesByCode.get(e.getKey()), e.getValue()[0], e.getValue()[1]))
                .toList();

        return new Result(List.copyOf(lines), subtotals, lineExtension, taxTotal, lineExtension.add(taxTotal));
    }

    private CalculatedLine calculateLine(int lineNumber, LineInput in) {
        if (in.quantity().signum() <= 0) {
            throw new DomainException("INVALID_QUANTITY", "Line %d: quantity must be positive".formatted(lineNumber));
        }
        if (in.unitPrice().signum() < 0) {
            throw new DomainException("INVALID_UNIT_PRICE", "Line %d: unit price must not be negative".formatted(lineNumber));
        }
        Set<TaxType> seenTypes = EnumSet.noneOf(TaxType.class);
        for (TaxRate rate : in.taxes()) {
            if (!seenTypes.add(rate.type())) {
                throw new DomainException("DUPLICATE_TAX_TYPE",
                        "Line %d: more than one %s rate".formatted(lineNumber, rate.type()));
            }
        }

        BigDecimal gross = in.quantity().multiply(in.unitPrice());
        BigDecimal discount = in.discount().amountFor(gross);
        if (discount.compareTo(gross) > 0) {
            throw new DomainException("DISCOUNT_EXCEEDS_GROSS",
                    "Line %d: discount is greater than the line amount".formatted(lineNumber));
        }
        BigDecimal taxable = rounding.apply(gross.subtract(discount));

        List<LineTax> taxes = new ArrayList<>(in.taxes().size());
        BigDecimal lineTax = rounding.zero();
        for (TaxRate rate : in.taxes()) {
            BigDecimal amount = rounding.apply(taxable.multiply(rate.fraction()));
            taxes.add(new LineTax(rate, taxable, amount));
            lineTax = lineTax.add(amount);
        }

        // El descuento informado se deriva para que bruto − descuento == base, exactamente,
        // aun cuando bruto y descuento tengan más decimales que la escala monetaria.
        BigDecimal roundedGross = rounding.apply(gross);
        return new CalculatedLine(
                roundedGross,
                roundedGross.subtract(taxable),
                taxable,
                List.copyOf(taxes),
                lineTax,
                taxable.add(lineTax));
    }
}
