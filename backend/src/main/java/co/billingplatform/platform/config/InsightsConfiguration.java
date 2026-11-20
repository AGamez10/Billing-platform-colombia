package co.billingplatform.platform.config;

import co.billingplatform.insights.application.InsightsService;
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
    InsightsService insightsService(InsightsService.FactsReader reader, Clock clock) {
        return new InsightsService(reader, clock);
    }
}
