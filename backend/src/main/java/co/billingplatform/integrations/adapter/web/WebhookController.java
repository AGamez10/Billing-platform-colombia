package co.billingplatform.integrations.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.integrations.application.WebhookService;
import co.billingplatform.integrations.domain.WebhookEventType;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Avisos salientes firmados (ADR-0033). Solo personas con permiso de integraciones; nunca una clave de API. */
@RestController
@RequestMapping("/api/v1/webhook-endpoints")
@Tag(name = "Integraciones")
@SecurityRequirement(name = "bearer")
class WebhookController {

    record EndpointRequest(@NotBlank @Size(max = 500) String url, @NotEmpty @Size(max = 10) List<@NotBlank String> events,
                           @Size(max = 200) String description) {}

    record EndpointView(UUID id, String url, List<String> events, String description, boolean active, Instant createdAt) {
        static EndpointView of(WebhookService.Endpoint e) {
            return new EndpointView(e.id(), e.url(), e.events().stream().map(WebhookEventType::wireName).sorted().toList(),
                    e.description(), e.active(), e.createdAt());
        }
    }

    record CreatedView(EndpointView endpoint, String secret) {}

    record DeliveryView(UUID id, String event, String status, int attempts, Instant nextAttemptAt, Integer lastStatus,
                        String lastError, Instant createdAt) {}

    private final WebhookService webhooks;
    private final TenantTransactions tx;

    WebhookController(WebhookService webhooks, TenantTransactions tx) {
        this.webhooks = webhooks;
        this.tx = tx;
    }

    private CurrentUser admin(Jwt jwt) {
        return CurrentUser.from(jwt).requireUser().require(Permission.API_KEYS_MANAGE);
    }

    @GetMapping
    @Operation(summary = "Endpoints registrados")
    List<EndpointView> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = admin(jwt);
        return tx.inTenant(u.tenantId(), () -> webhooks.list(u.tenantId()).stream().map(EndpointView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un endpoint HTTPS público. Eventos: document.accepted, document.rejected, document.failed. "
            + "El secreto de firma se devuelve una sola vez.")
    CreatedView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EndpointRequest r) {
        CurrentUser u = admin(jwt);
        var registered = tx.inTenant(u.tenantId(), () -> webhooks.register(u.tenantId(), u.actor(), r.url(), r.events(), r.description()));
        return new CreatedView(EndpointView.of(registered.endpoint()), registered.secret());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar un endpoint (las entregas pendientes se descartan)")
    void disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = admin(jwt);
        tx.inTenant(u.tenantId(), () -> {
            webhooks.disable(u.tenantId(), u.actor(), id);
            return null;
        });
    }

    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Enviar un aviso de prueba (ping)")
    Map<String, UUID> test(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = admin(jwt);
        return Map.of("deliveryId", tx.inTenant(u.tenantId(), () -> webhooks.test(u.tenantId(), id)));
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "Últimas 20 entregas con su estado")
    List<DeliveryView> deliveries(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = admin(jwt);
        return tx.inTenant(u.tenantId(), () -> webhooks.deliveries(u.tenantId(), id).stream()
                .map(d -> new DeliveryView(d.id(), d.event().wireName(), d.status(), d.attempts(), d.nextAttemptAt(), d.lastStatus(),
                        d.lastError(), d.createdAt())).toList());
    }
}
