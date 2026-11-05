package co.billingplatform.tax.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tarifas para pruebas. Las que citan el Estatuto Tributario reflejan las tarifas del
 * seed de la migración V2 (verificadas el 2026-11-05, ver docs/DECISIONS.md); las
 * marcadas como "test fixture" son ficticias y solo existen para probar vigencias.
 */
public final class TestTaxRates {

    private static final LocalDate SINCE = LocalDate.of(2017, 1, 1);

    public static final TaxRate IVA_19 = new TaxRate("IVA_19", TaxType.IVA, TaxTreatment.TAXED,
            new BigDecimal("19"), SINCE, null, "Estatuto Tributario art. 468");
    public static final TaxRate IVA_5 = new TaxRate("IVA_5", TaxType.IVA, TaxTreatment.TAXED,
            new BigDecimal("5"), SINCE, null, "Estatuto Tributario art. 468-1");
    public static final TaxRate IVA_EXEMPT = new TaxRate("IVA_EXENTO", TaxType.IVA, TaxTreatment.EXEMPT,
            BigDecimal.ZERO, SINCE, null, "Estatuto Tributario art. 477 y ss.");
    public static final TaxRate IVA_EXCLUDED = new TaxRate("IVA_EXCLUIDO", TaxType.IVA, TaxTreatment.EXCLUDED,
            BigDecimal.ZERO, SINCE, null, "Estatuto Tributario art. 424 y ss.");
    public static final TaxRate INC_8 = new TaxRate("INC_8", TaxType.INC, TaxTreatment.TAXED,
            new BigDecimal("8"), SINCE, null, "Estatuto Tributario art. 512-9");

    private TestTaxRates() {}

    public static TaxCatalog catalog() {
        return TaxCatalog.of(List.of(IVA_19, IVA_5, IVA_EXEMPT, IVA_EXCLUDED, INC_8));
    }
}
