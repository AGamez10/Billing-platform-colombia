package co.billingplatform.subscriptions.adapter.web;

import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.subscriptions.application.SubscriptionCheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Aviso de Mercado Pago sobre pagos de NUESTROS planes. Público, autenticado con la firma
 * {@code x-signature}; el contenido no se cree: el pago se consulta a la API de Mercado Pago.
 */
@RestController
@Tag(name = "Suscripción y consumo")
class MercadoPagoWebhookController {

    private final ObjectProvider<SubscriptionCheckoutService> checkouts;
    private final TenantTransactions tx;

    MercadoPagoWebhookController(ObjectProvider<SubscriptionCheckoutService> checkouts, TenantTransactions tx) {
        this.checkouts = checkouts;
        this.tx = tx;
    }

    @PostMapping("/api/v1/webhooks/mercadopago")
    @Operation(summary = "Aviso de pago de Mercado Pago (configurado en el panel de la aplicación de Mercado Pago).")
    ResponseEntity<Map<String, Object>> notify(@RequestParam(name = "data.id", required = false) String dataId,
                                               @RequestParam(name = "type", required = false) String type,
                                               @RequestHeader(name = "x-signature", required = false) String signature,
                                               @RequestHeader(name = "x-request-id", required = false) String requestId) {
        SubscriptionCheckoutService service = checkouts.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!service.verifyWebhook(dataId, requestId, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"payment".equals(type) || dataId == null) {
            return ResponseEntity.ok(Map.of("received", true, "ignored", true));
        }
        Optional<SubscriptionCheckoutService.CheckoutRef> ref = tx.withoutTenant(() -> service.checkoutOfPayment(dataId));
        if (ref.isEmpty()) {
            // Pago que no es de un plan (u otro comercio de la cuenta): se confirma para que no se reintente.
            return ResponseEntity.ok(Map.of("received", true, "ignored", true));
        }
        var state = tx.inTenant(ref.get().tenantId(), () -> service.reconcile(ref.get().tenantId(), ref.get().checkoutId()));
        return ResponseEntity.ok(Map.of("received", true, "status", state.checkout().status().name()));
    }
}
