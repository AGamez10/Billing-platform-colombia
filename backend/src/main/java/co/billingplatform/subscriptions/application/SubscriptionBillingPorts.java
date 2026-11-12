package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.SubscriptionCheckout;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puertos del cobro de nuestras suscripciones (ADR-0024). */
public final class SubscriptionBillingPorts {

    private SubscriptionBillingPorts() {}

    /** Pasarela con la que la plataforma cobra sus planes (Mercado Pago). */
    public interface PaymentGateway {

        record Preference(String id, String checkoutUrl) {}

        enum PaymentStatus { APPROVED, PENDING, REJECTED, REFUNDED }

        record Payment(String id, PaymentStatus status, String externalReference, BigDecimal amount, String currency,
                       boolean liveMode) {}

        record Request(String externalReference, String title, BigDecimal amount, URI notificationUrl, URI successUrl,
                       URI pendingUrl, URI failureUrl, Instant expiresAt) {}

        Preference createPreference(Request request);

        Optional<Payment> payment(String paymentId);

        /** Pagos (intentos) asociados a una referencia, del más reciente al más antiguo. */
        List<Payment> paymentsFor(String externalReference);

        /** Firma del aviso. {@code dataId} y {@code requestId} como llegan en la petición. */
        boolean verifyWebhook(String dataId, String requestId, String signatureHeader);

        final class GatewayException extends RuntimeException {
            private static final long serialVersionUID = 1L;

            public GatewayException(String message) {
                super(message);
            }
        }
    }

    public interface Checkouts {
        void insert(SubscriptionCheckout checkout);

        void update(SubscriptionCheckout checkout);

        Optional<SubscriptionCheckout> lock(TenantId tenantId, UUID id);

        /** Sin tenant en contexto ({@code subscription_checkout_tenant}). */
        Optional<TenantId> tenantOf(UUID id);
    }

    /** Cambios de estado de la suscripción por pagos y vencimientos. */
    public interface Renewals {
        void activate(TenantId tenantId, UUID subscriptionId, String planCode, LocalDate periodStart, LocalDate periodEnd);

        /** Sin tenant: pasa a PAST_DUE lo vencido hace más de {@code graceDays}. */
        int markOverdue(LocalDate today, int graceDays);
    }
}
