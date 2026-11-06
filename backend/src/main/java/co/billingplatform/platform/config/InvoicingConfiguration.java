package co.billingplatform.platform.config;

import co.billingplatform.customers.application.CustomerService;
import co.billingplatform.electronicinvoicing.application.ProviderAccountService;
import co.billingplatform.electronicinvoicing.application.ProviderRegistry;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.electronicinvoicing.port.SecretResolver;
import co.billingplatform.invoicing.application.FinalizeInvoiceService;
import co.billingplatform.invoicing.application.InvoiceDraftService;
import co.billingplatform.invoicing.application.InvoiceRepository;
import co.billingplatform.invoicing.application.IssuerDirectory;
import co.billingplatform.invoicing.application.NumberingRangeRepository;
import co.billingplatform.invoicing.application.NumberingRangeService;
import co.billingplatform.invoicing.application.PosSaleService;
import co.billingplatform.invoicing.application.SubmissionQueue;
import co.billingplatform.invoicing.application.SubmitInvoiceService;
import co.billingplatform.invoicing.application.UsageMeter;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.RoundingPolicy;
import co.billingplatform.subscriptions.application.OverageConsentRepository;
import co.billingplatform.subscriptions.application.OverageConsentService;
import co.billingplatform.subscriptions.application.SubscriptionRepository;
import co.billingplatform.subscriptions.application.SubscriptionUsageMeter;
import co.billingplatform.subscriptions.application.UsageRepository;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.List;

/** Ensambla facturación, proveedores y suscripciones. */
@Configuration
@EnableScheduling
class InvoicingConfiguration {

    @Bean
    DocumentCalculator documentCalculator() {
        return new DocumentCalculator(RoundingPolicy.COP_DEFAULT);
    }

    /**
     * Sin almacén de secretos todavía: ningún adaptador desplegado necesita credenciales.
     * Falla de forma explícita si alguien intenta resolver uno (nunca devuelve un valor vacío).
     */
    @Bean
    SecretResolver secretResolver() {
        return ref -> {
            throw new IllegalStateException("No secret store configured");
        };
    }

    @Bean
    ProviderRegistry providerRegistry(List<ElectronicInvoicingProvider> providers) {
        return new ProviderRegistry(providers);
    }

    @Bean
    ProviderAccountService providerAccountService(ProviderAccountRepository accounts, ProviderRegistry registry,
                                                  AuditTrail audit) {
        return new ProviderAccountService(accounts, registry, audit);
    }

    @Bean
    NumberingRangeService numberingRangeService(NumberingRangeRepository ranges, AuditTrail audit) {
        return new NumberingRangeService(ranges, audit);
    }

    @Bean
    SubscriptionUsageMeter subscriptionUsageMeter(SubscriptionRepository subscriptions, UsageRepository usage,
                                                  OverageConsentRepository consents, Clock clock) {
        return new SubscriptionUsageMeter(subscriptions, usage, consents, clock);
    }

    @Bean
    OverageConsentService overageConsentService(SubscriptionRepository subscriptions, UsageRepository usage,
                                                OverageConsentRepository consents, AuditTrail audit, Clock clock) {
        return new OverageConsentService(subscriptions, usage, consents, audit, clock);
    }

    @Bean
    InvoiceDraftService invoiceDraftService(InvoiceRepository invoices, CustomerService customers, TaxCatalog taxes,
                                            DocumentCalculator calculator, AuditTrail audit, Clock clock) {
        return new InvoiceDraftService(invoices, customers, taxes, calculator, audit, clock);
    }

    @Bean
    FinalizeInvoiceService finalizeInvoiceService(InvoiceRepository invoices, NumberingRangeRepository ranges,
                                                  ProviderAccountRepository accounts, SubmissionQueue queue,
                                                  UsageMeter usage, TaxCatalog taxes, DocumentCalculator calculator,
                                                  AuditTrail audit, Clock clock) {
        return new FinalizeInvoiceService(invoices, ranges, accounts, queue, usage, taxes, calculator, audit, clock);
    }

    @Bean
    PosSaleService posSaleService(InvoiceDraftService drafts, FinalizeInvoiceService finalizer, InvoiceRepository invoices,
                                  PosSaleService.IdempotencyKeys keys) {
        return new PosSaleService(drafts, finalizer, invoices, keys);
    }

    @Bean
    SubmitInvoiceService submitInvoiceService(InvoiceRepository invoices, ProviderAccountRepository accounts,
                                              ProviderRegistry registry, IssuerDirectory issuers, Clock clock,
                                              @Value("${billing.submission.max-provider-interactions:10}") int maxInteractions) {
        return new SubmitInvoiceService(invoices, accounts, registry, issuers, clock, maxInteractions);
    }
}
