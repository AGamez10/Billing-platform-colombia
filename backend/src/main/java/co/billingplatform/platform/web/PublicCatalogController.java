package co.billingplatform.platform.web;

import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.subscriptions.application.PlanCatalog;
import co.billingplatform.tax.adapter.persistence.JdbcTaxCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** Información pública, sin autenticación ni datos de organizaciones. */
@RestController
@RequestMapping("/api/v1/public")
class PublicCatalogController {

    record TaxRateView(String code, String type, String treatment, String percentage, String validFrom,
                       String validTo, String legalReference) {}

    private final PlanCatalog plans;
    private final JdbcTaxCatalog taxes;
    private final Clock clock = Clock.systemUTC();

    PublicCatalogController(PlanCatalog plans, JdbcTaxCatalog taxes) {
        this.plans = plans;
        this.taxes = taxes;
    }

    @GetMapping("/plans")
    List<PlanCatalog.PublicPlan> plans() {
        return plans.publicPlans();
    }

    /** Tarifas vigentes hoy (hora de Colombia), con su fundamento legal. */
    @GetMapping("/tax-rates")
    List<TaxRateView> taxRates() {
        return taxes.validOn(ColombianTime.today(clock)).stream()
                .map(r -> new TaxRateView(r.code(), r.type().name(), r.treatment().name(),
                        r.percentage().toPlainString(), r.validFrom().toString(),
                        r.validTo() == null ? null : r.validTo().toString(), r.legalReference()))
                .toList();
    }
}
