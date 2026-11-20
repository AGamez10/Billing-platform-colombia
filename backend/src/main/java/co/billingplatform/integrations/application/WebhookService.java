package co.billingplatform.integrations.application;

import co.billingplatform.integrations.domain.WebhookEventType;
import co.billingplatform.integrations.domain.WebhookSignature;
import co.billingplatform.integrations.domain.WebhookTarget;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.shared.domain.TenantId;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Avisos salientes firmados a otras apps (ADR-0033): la organización registra una URL HTTPS y
 * los eventos que le interesan; cada evento queda en una bandeja de salida y un trabajador lo
 * entrega con reintentos exponenciales. El secreto de firma se muestra una sola vez y se guarda
 * cifrado.
 */
public final class WebhookService {

    public static final int MAX_ENDPOINTS = 5;
    public static final int MAX_ATTEMPTS = 8;
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));

    public record Endpoint(TenantId tenantId, UUID id, String url, Set<WebhookEventType> events, String secretRef,
                           String description, boolean active, Instant createdAt) {}

    public record Delivery(TenantId tenantId, UUID id, UUID endpointId, WebhookEventType event, String payload, String status,
                           int attempts, Instant nextAttemptAt, Integer lastStatus, String lastError, Instant createdAt) {}

    public record Registered(Endpoint endpoint, String secret) {
        @Override
        public String toString() {
            return "Registered[" + endpoint.id() + "]";
        }
    }

    public interface Store {
        List<Endpoint> endpoints(TenantId tenantId);

        Optional<Endpoint> endpoint(TenantId tenantId, UUID id);

        void insert(Endpoint endpoint);

        void disable(TenantId tenantId, UUID id);

        void enqueue(Delivery delivery);

        Optional<Delivery> delivery(TenantId tenantId, UUID id);

        void recordAttempt(TenantId tenantId, UUID id, String status, int attempts, Instant nextAttemptAt, Integer lastStatus,
                           String lastError, Instant deliveredAt);

        List<Delivery> recentDeliveries(TenantId tenantId, UUID endpointId, int limit);
    }

    public interface Secrets {
        void store(TenantId tenantId, String ref, String value);

        String read(String ref);
    }

    /** Envío HTTP con protección SSRF (resuelve el host y rechaza redes privadas) y sin redirecciones. */
    public interface Sender {
        /** @return código HTTP de la respuesta; lanza {@link SendException} si no hubo respuesta o el destino no es público */
        int post(URI url, Map<String, String> headers, String body);

        final class SendException extends RuntimeException {
            private static final long serialVersionUID = 1L;

            public SendException(String message) {
                super(message);
            }
        }
    }

    private final Store store;
    private final Secrets secrets;
    private final Sender sender;
    private final AuditTrail audit;
    private final Clock clock;

    public WebhookService(Store store, Secrets secrets, Sender sender, AuditTrail audit, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.secrets = Objects.requireNonNull(secrets);
        this.sender = Objects.requireNonNull(sender);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Registered register(TenantId tenantId, Actor actor, String url, List<String> events, String description) {
        URI target = WebhookTarget.validate(url);
        if (events == null || events.isEmpty()) {
            throw new DomainException("INVALID_WEBHOOK_EVENT", "Choose at least one event");
        }
        Set<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        events.forEach(e -> types.add(WebhookEventType.ofWire(e)));
        if (store.endpoints(tenantId).stream().filter(Endpoint::active).count() >= MAX_ENDPOINTS) {
            throw new DomainException("WEBHOOK_LIMIT_REACHED", "At most " + MAX_ENDPOINTS + " active endpoints");
        }
        String desc = description == null || description.isBlank() ? null : description.strip();
        if (desc != null && desc.length() > 200) {
            throw new DomainException("INVALID_WEBHOOK_URL", "Description is too long");
        }
        UUID id = UUID.randomUUID();
        String secret = "whsec_" + OpaqueTokens.randomBase64Url(32);
        String ref = "webhook:" + id;
        secrets.store(tenantId, ref, secret);
        Endpoint endpoint = new Endpoint(tenantId, id, target.toString(), Set.copyOf(types), ref, desc, true, clock.instant());
        store.insert(endpoint);
        audit.record(new AuditTrail.Event(tenantId, actor, "WEBHOOK_ENDPOINT_CREATED", "webhook_endpoint", id.toString(),
                Map.of("host", target.getHost(), "events", String.join(",", events))));
        return new Registered(endpoint, secret);
    }

    public List<Endpoint> list(TenantId tenantId) {
        return store.endpoints(tenantId);
    }

    public void disable(TenantId tenantId, Actor actor, UUID id) {
        store.endpoint(tenantId, id).orElseThrow(WebhookService::notFound);
        store.disable(tenantId, id);
        audit.record(new AuditTrail.Event(tenantId, actor, "WEBHOOK_ENDPOINT_DISABLED", "webhook_endpoint", id.toString(), Map.of()));
    }

    /** Encola un {@code ping} para probar el endpoint; llega como cualquier otro aviso. */
    public UUID test(TenantId tenantId, UUID id) {
        Endpoint e = store.endpoint(tenantId, id).filter(Endpoint::active).orElseThrow(WebhookService::notFound);
        return enqueue(e, WebhookEventType.PING, "{\"message\":\"Hola desde la plataforma de facturación\"}");
    }

    public List<Delivery> deliveries(TenantId tenantId, UUID endpointId) {
        store.endpoint(tenantId, endpointId).orElseThrow(WebhookService::notFound);
        return store.recentDeliveries(tenantId, endpointId, 20);
    }

    /** Dentro del tenant: un evento para cada endpoint activo suscrito. */
    public void publish(TenantId tenantId, WebhookEventType type, String dataJson) {
        for (Endpoint e : store.endpoints(tenantId)) {
            if (e.active() && e.events().contains(type)) {
                enqueue(e, type, dataJson);
            }
        }
    }

    private UUID enqueue(Endpoint e, WebhookEventType type, String dataJson) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String payload = "{\"id\":\"" + id + "\",\"type\":\"" + type.wireName() + "\",\"createdAt\":\"" + now + "\",\"data\":" + dataJson + "}";
        store.enqueue(new Delivery(e.tenantId(), id, e.id(), type, payload, "PENDING", 0, now, null, null, now));
        return id;
    }

    /** Dentro del tenant: intenta una entrega y programa el siguiente intento si falla. */
    public void deliver(TenantId tenantId, UUID deliveryId) {
        Delivery d = store.delivery(tenantId, deliveryId).orElse(null);
        if (d == null || !"PENDING".equals(d.status())) {
            return;
        }
        Endpoint e = store.endpoint(tenantId, d.endpointId()).orElse(null);
        int attempts = d.attempts() + 1;
        Instant now = clock.instant();
        if (e == null || !e.active()) {
            store.recordAttempt(tenantId, d.id(), "FAILED", attempts, now, null, "Endpoint disabled", null);
            return;
        }
        long epoch = now.getEpochSecond();
        Integer status = null;
        String error;
        try {
            status = sender.post(URI.create(e.url()), Map.of(
                    "Content-Type", "application/json",
                    "User-Agent", "BillingPlatform-Webhooks/1",
                    "X-Billing-Event", d.event().wireName(),
                    "X-Billing-Delivery", d.id().toString(),
                    "X-Billing-Signature", WebhookSignature.header(secrets.read(e.secretRef()), epoch, d.payload())), d.payload());
            if (status >= 200 && status < 300) {
                store.recordAttempt(tenantId, d.id(), "DELIVERED", attempts, now, status, null, now);
                return;
            }
            error = "HTTP " + status;
        } catch (Sender.SendException ex) {
            error = ex.getMessage();
        }
        boolean exhausted = attempts >= MAX_ATTEMPTS;
        Instant next = exhausted ? now : now.plus(BACKOFF.get(Math.min(attempts - 1, BACKOFF.size() - 1)));
        store.recordAttempt(tenantId, d.id(), exhausted ? "FAILED" : "PENDING", attempts, next, status,
                error == null ? null : error.substring(0, Math.min(300, error.length())), null);
    }

    private static DomainException notFound() {
        return new DomainException("WEBHOOK_ENDPOINT_NOT_FOUND", "Webhook endpoint not found");
    }
}
