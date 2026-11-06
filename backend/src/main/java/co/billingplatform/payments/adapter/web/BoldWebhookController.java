package co.billingplatform.payments.adapter.web;

import co.billingplatform.payments.application.ChargeService;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.DomainException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notificaciones de Bold. Público (sin sesión) y autenticado en dos capas: el token opaco de la
 * URL ubica la cuenta (se guarda solo su hash) y la firma {@code x-bold-signature} se verifica
 * con la llave secreta de ESA cuenta. Su contenido nunca se toma como verdad: solo dispara la
 * consulta a la pasarela de los cobros pendientes del comercio.
 */
@RestController
@Tag(name = "Cobros: webhooks")
class BoldWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BoldWebhookController.class);
    static final int MAX_BODY_BYTES = 64 * 1024;
    static final int MAX_CHARGES_PER_NOTIFICATION = 20;

    private final ChargeService charges;
    private final TenantTransactions tx;

    BoldWebhookController(ChargeService charges, TenantTransactions tx) {
        this.charges = charges;
        this.tx = tx;
    }

    @PostMapping("/api/v1/webhooks/bold/{token}")
    @Operation(summary = "Webhook de Bold (configurado por el comercio en su panel con la ruta que entrega la plataforma).")
    ResponseEntity<Map<String, Object>> notify(@PathVariable String token,
                                               @RequestHeader(name = "x-bold-signature", required = false) String signature,
                                               @RequestBody(required = false) byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES || token.length() > 100) {
            return ResponseEntity.badRequest().build();
        }
        PaymentAccount account = tx.withoutTenant(() -> charges.webhookAccount(token)).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<UUID> pending = tx.inTenant(account.tenantId(), () -> charges.verifyWebhook(account, body, signature));
        int changed = 0;
        for (UUID id : pending.stream().limit(MAX_CHARGES_PER_NOTIFICATION).toList()) {
            try {
                if (tx.inTenant(account.tenantId(), () -> charges.refresh(account.tenantId(), id)).charge().status().isFinal()) {
                    changed++;
                }
            } catch (DomainException | org.springframework.dao.DataAccessException e) {
                // La conciliación periódica lo reintenta; el webhook igual se confirma.
                log.warn("Charge {} could not be refreshed from webhook: {}", id, e.toString());
            }
        }
        return ResponseEntity.ok(Map.of("received", true, "resolved", changed));
    }
}
