package co.billingplatform.platform.config;

import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.subscriptions.adapter.mercadopago.MercadoPagoGateway;
import co.billingplatform.subscriptions.application.PlanCatalog;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts;
import co.billingplatform.subscriptions.application.SubscriptionCheckoutService;
import co.billingplatform.subscriptions.application.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Pago de nuestros planes con Mercado Pago (ADR-0024). Solo se activa con un access token
 * (variable de entorno, nunca en el repositorio); sin él la app funciona y el pago en línea
 * aparece deshabilitado.
 */
@Configuration
@ConditionalOnExpression("'${billing.mercadopago.access-token:}' != ''")
class SubscriptionPaymentsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentsConfiguration.class);

    @Bean
    MercadoPagoGateway mercadoPagoGateway(@Value("${billing.mercadopago.base-url:https://api.mercadopago.com}") URI baseUrl,
                                          @Value("${billing.mercadopago.access-token}") String accessToken,
                                          @Value("${billing.mercadopago.webhook-secret:}") String webhookSecret,
                                          @Value("${billing.mercadopago.timeout:10s}") Duration timeout) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("BILLING_MERCADOPAGO_WEBHOOK_SECRET is empty: Mercado Pago notifications will be rejected; "
                    + "plan payments are still confirmed by polling every few minutes");
        }
        return new MercadoPagoGateway(baseUrl, accessToken, webhookSecret, timeout);
    }

    @Bean
    SubscriptionCheckoutService subscriptionCheckoutService(SubscriptionRepository subscriptions, PlanCatalog catalog,
                                                            SubscriptionBillingPorts.Checkouts checkouts,
                                                            SubscriptionBillingPorts.Renewals renewals,
                                                            MercadoPagoGateway gateway,
                                                            @Value("${billing.web.base-url}") String baseUrl,
                                                            @Value("${billing.mercadopago.require-live-mode:true}") boolean requireLiveMode,
                                                            @Value("${billing.mercadopago.access-token}") String accessToken,
                                                            AuditTrail audit, Clock clock) {
        if (requireLiveMode && accessToken.strip().startsWith("TEST-")) {
            log.warn("Mercado Pago test credentials with BILLING_MERCADOPAGO_REQUIRE_LIVE_MODE=true: "
                    + "test payments will never activate a plan (expected in production, use false only for testing)");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new SubscriptionCheckoutService(subscriptions, catalog, checkouts, renewals, gateway,
                new SubscriptionCheckoutService.Links(URI.create(base + "/api/v1/webhooks/mercadopago"), URI.create(base + "/plan")),
                requireLiveMode, audit, clock);
    }
}
