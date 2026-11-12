package co.billingplatform.platform.web;

import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.subscriptions.application.PlanCatalog;
import co.billingplatform.tax.adapter.persistence.JdbcTaxCatalog;
import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.shared.application.Municipalities;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Información pública, sin autenticación ni datos de organizaciones. */
@RestController
@RequestMapping("/api/v1/public")
class PublicCatalogController {

    record TaxRateView(String code, String type, String treatment, String percentage, String validFrom,
                       String validTo, String legalReference) {}

    record LegalView(String termsVersion, String privacyVersion) {}

    private final PlanCatalog plans;
    private final JdbcTaxCatalog taxes;
    private final IdentityPorts.LegalVersions legal;
    private final Municipalities municipalities;
    private final Clock clock = Clock.systemUTC();

    PublicCatalogController(PlanCatalog plans, JdbcTaxCatalog taxes, IdentityPorts.LegalVersions legal,
                            Municipalities municipalities) {
        this.plans = plans;
        this.taxes = taxes;
        this.legal = legal;
        this.municipalities = municipalities;
    }

    /**
     * Municipios de Colombia (DIVIPOLA, DANE). Lista completa (~1.100, ~25 KB comprimida) para que
     * el selector busque en el navegador sin una consulta por tecla; se puede guardar en caché.
     */
    @GetMapping("/municipalities")
    ResponseEntity<List<Municipalities.Municipality>> municipalities() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(municipalities.all());
    }

    /** Versiones vigentes que el registro debe aceptar. */
    @GetMapping("/legal")
    LegalView legal() {
        return new LegalView(legal.terms(), legal.privacy());
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
