package co.billingplatform.platform.config;

import co.billingplatform.integrations.adapter.http.SafeWebhookSender;
import co.billingplatform.integrations.application.DocumentWebhooks;
import co.billingplatform.integrations.application.WebhookService;
import co.billingplatform.invoicing.application.InvoiceEvents;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.application.AuditTrail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class IntegrationsConfiguration {

    @Bean
    WebhookService webhookService(WebhookService.Store store, PaymentPorts.SecretVault vault, AuditTrail audit, Clock clock,
                                  @Value("${billing.webhooks.timeout:10s}") Duration timeout) {
        WebhookService.Secrets secrets = new WebhookService.Secrets() {
            @Override public void store(co.billingplatform.shared.domain.TenantId t, String ref, String value) { vault.store(t, ref, value); }
            @Override public String read(String ref) { return vault.read(ref); }
        };
        return new WebhookService(store, secrets, new SafeWebhookSender(timeout), audit, clock);
    }

    @Bean
    InvoiceEvents invoiceEvents(WebhookService webhooks) {
        return new DocumentWebhooks(webhooks);
    }
}
