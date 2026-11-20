package co.billingplatform.platform.config;

import co.billingplatform.insights.application.AlertNotifier;
import co.billingplatform.insights.application.InsightsService;
import co.billingplatform.shared.application.Mailer;
import org.springframework.beans.factory.annotation.Value;
import co.billingplatform.subscriptions.application.OverageConsentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class InsightsConfiguration {

    @Bean
    InsightsService.QuotaReader insightsQuota(OverageConsentService overage) {
        return tenantId -> {
            var s = overage.status(tenantId);
            return new InsightsService.Quota(s.limit(), (int) s.used(), "TRIAL".equals(s.planCode()));
        };
    }

    @Bean
    AlertNotifier alertNotifier(InsightsService insights, AlertNotifier.Ledger ledger, Mailer mailer,
                                @Value("${billing.brand.name}") String brand, @Value("${billing.web.base-url}") String baseUrl) {
        return new AlertNotifier(insights, ledger, mailer, brand, baseUrl);
    }

    @Bean
    InsightsService insightsService(InsightsService.FactsReader reader, Clock clock) {
        return new InsightsService(reader, clock);
    }
}
