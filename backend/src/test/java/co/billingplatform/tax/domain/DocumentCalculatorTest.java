package co.billingplatform.tax.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.RoundingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static co.billingplatform.tax.domain.TestTaxRates.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentCalculatorTest {

    private final DocumentCalculator calculator = new DocumentCalculator(RoundingPolicy.COP_DEFAULT);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static DocumentCalculator.LineInput line(String qty, String price, Discount discount, TaxRate... taxes) {
        return new DocumentCalculator.LineInput(d(qty), d(price), discount, List.of(taxes));
    }

    @Test
    @DisplayName("calcula base, IVA 19% y total de una línea simple")
    void simpleLine() {
        var result = calculator.calculate(List.of(line("2", "10000", null, IVA_19)));

        assertEquals(d("20000.00"), result.lineExtensionAmount());
        assertEquals(d("3800.00"), result.taxTotal());
        assertEquals(d("23800.00"), result.payableAmount());
        assertEquals(1, result.taxSubtotals().size());
        assertEquals(d("20000.00"), result.taxSubtotals().getFirst().taxableAmount());
    }

    @Test
    @DisplayName("aplica descuento porcentual antes de impuestos y redondea por línea")
    void percentageDiscount() {
        var result = calculator.calculate(List.of(
                line("3", "33333.33", new Discount.Percentage(d("10")), IVA_19)));

        var computed = result.lines().getFirst();
        assertEquals(d("99999.99"), computed.grossAmount());
        assertEquals(d("10000.00"), computed.discountAmount());
        assertEquals(d("89999.99"), computed.taxableAmount());   // 99999.99 - 9999.999 = 89999.991
        assertEquals(d("17100.00"), computed.taxTotal());        // 89999.99 * 0.19 = 17099.9981
        assertEquals(d("107099.99"), result.payableAmount());
    }

    @Test
    @DisplayName("usa HALF_UP explícito: 7.50 × 19% = 1.425 → 1.43")
    void halfUpRounding() {
        var result = calculator.calculate(List.of(line("1", "7.50", null, IVA_19)));
        assertEquals(d("1.43"), result.taxTotal());

        var halfEven = new DocumentCalculator(new RoundingPolicy(2, RoundingMode.HALF_EVEN))
                .calculate(List.of(line("1", "7.50", null, IVA_19)));
        assertEquals(d("1.42"), halfEven.taxTotal(), "la política de redondeo es configurable y explícita");
    }

    @Test
    @DisplayName("agrupa subtotales por tarifa y distingue exento de excluido")
    void groupsSubtotalsByRate() {
        var result = calculator.calculate(List.of(
                line("1", "100000", null, IVA_19),
                line("2", "5000", null, IVA_5),
                line("1", "3000", null, IVA_EXCLUDED),
                line("1", "2000", null, IVA_EXEMPT),
                line("1", "50000", new Discount.Amount(d("5000")), IVA_19)));

        assertEquals(4, result.taxSubtotals().size());
        var iva19 = result.taxSubtotals().stream().filter(s -> s.rate() == IVA_19).findFirst().orElseThrow();
        assertEquals(d("145000.00"), iva19.taxableAmount());
        assertEquals(d("27550.00"), iva19.taxAmount());
        var excluded = result.taxSubtotals().stream().filter(s -> s.rate() == IVA_EXCLUDED).findFirst().orElseThrow();
        assertEquals(d("0.00"), excluded.taxAmount());
        assertEquals(TaxTreatment.EXCLUDED, excluded.rate().treatment());

        assertEquals(d("160000.00"), result.lineExtensionAmount());
        assertEquals(d("28050.00"), result.taxTotal());
        assertEquals(d("188050.00"), result.payableAmount());
    }

    @Test
    @DisplayName("una línea de restaurante puede llevar INC 8% sin IVA")
    void consumptionTax() {
        var result = calculator.calculate(List.of(line("2", "25000", null, INC_8)));
        assertEquals(d("4000.00"), result.taxTotal());
        assertEquals(TaxType.INC, result.taxSubtotals().getFirst().rate().type());
    }

    @Test
    @DisplayName("propiedad: los totales cuadran exactamente con el detalle (500 documentos aleatorios)")
    void totalsAlwaysMatchDetail() {
        Random random = new Random(20261105L);
        TaxRate[] rates = {IVA_19, IVA_5, IVA_EXEMPT, IVA_EXCLUDED, INC_8};
        for (int doc = 0; doc < 500; doc++) {
            List<DocumentCalculator.LineInput> lines = new ArrayList<>();
            int count = 1 + random.nextInt(8);
            for (int i = 0; i < count; i++) {
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(50_000), 3);
                BigDecimal price = BigDecimal.valueOf(random.nextInt(10_000_000), 2);
                Discount discount = random.nextBoolean() ? Discount.NONE
                        : new Discount.Percentage(BigDecimal.valueOf(random.nextInt(5000), 2));
                lines.add(new DocumentCalculator.LineInput(qty, price, discount, List.of(rates[random.nextInt(rates.length)])));
            }
            var result = calculator.calculate(lines);

            for (var l : result.lines()) {
                assertEquals(0, l.grossAmount().subtract(l.discountAmount()).compareTo(l.taxableAmount()),
                        "bruto − descuento == base en cada línea");
            }
            BigDecimal sumLines = result.lines().stream().map(DocumentCalculator.CalculatedLine::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sumSubtotalTax = result.taxSubtotals().stream().map(DocumentCalculator.TaxSubtotal::taxAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, sumLines.compareTo(result.payableAmount()), "payable == Σ líneas");
            assertEquals(0, sumSubtotalTax.compareTo(result.taxTotal()), "impuestos == Σ subtotales");
            assertEquals(2, result.payableAmount().scale(), "escala monetaria fija");
        }
    }

    @Test
    @DisplayName("rechaza documentos y líneas inválidas con códigos estables")
    void rejectsInvalidInput() {
        assertEquals("DOCUMENT_WITHOUT_LINES",
                assertThrows(DomainException.class, () -> calculator.calculate(List.of())).code());
        assertEquals("INVALID_QUANTITY",
                assertThrows(DomainException.class, () -> calculator.calculate(List.of(line("0", "100", null)))).code());
        assertEquals("INVALID_UNIT_PRICE",
                assertThrows(DomainException.class, () -> calculator.calculate(List.of(line("1", "-1", null)))).code());
        assertEquals("DISCOUNT_EXCEEDS_GROSS",
                assertThrows(DomainException.class, () -> calculator.calculate(
                        List.of(line("1", "100", new Discount.Amount(d("100.01")))))).code());
        assertEquals("DUPLICATE_TAX_TYPE",
                assertThrows(DomainException.class, () -> calculator.calculate(
                        List.of(line("1", "100", null, IVA_19, IVA_5)))).code());
    }

    @Test
    @DisplayName("una tarifa exenta o excluida con porcentaje distinto de cero es inválida")
    void exemptRateMustBeZero() {
        assertThrows(IllegalArgumentException.class, () -> new TaxRate("BAD", TaxType.IVA, TaxTreatment.EXEMPT,
                d("19"), LocalDate.of(2026, 1, 1), null, "test fixture"));
        assertThrows(IllegalArgumentException.class, () -> new TaxRate("NOREF", TaxType.IVA, TaxTreatment.TAXED,
                d("19"), LocalDate.of(2026, 1, 1), null, " "));
    }
}
