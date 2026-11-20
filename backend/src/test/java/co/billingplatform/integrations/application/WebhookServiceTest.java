package co.billingplatform.integrations.application;

import co.billingplatform.integrations.domain.WebhookEventType;
import co.billingplatform.integrations.domain.WebhookSignature;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebhookServiceTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Actor actor = Actor.user(UUID.randomUUID());
    private Instant now = Instant.parse("2026-11-19T15:00:00Z");
    private final Map<UUID, WebhookService.Endpoint> endpoints = new HashMap<>();
    private final Map<UUID, WebhookService.Delivery> deliveries = new HashMap<>();
    private final Map<String, String> secrets = new HashMap<>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<>();
    private final List<String> sentBodies = new ArrayList<>();
    private int nextStatus = 200;
    private boolean unreachable = false;

    private final WebhookService.Store store = new WebhookService.Store() {
        @Override public List<WebhookService.Endpoint> endpoints(TenantId t) { return List.copyOf(endpoints.values()); }
        @Override public Optional<WebhookService.Endpoint> endpoint(TenantId t, UUID id) { return Optional.ofNullable(endpoints.get(id)); }
        @Override public void insert(WebhookService.Endpoint e) { endpoints.put(e.id(), e); }
        @Override public void disable(TenantId t, UUID id) {
            var e = endpoints.get(id);
            endpoints.put(id, new WebhookService.Endpoint(e.tenantId(), e.id(), e.url(), e.events(), e.secretRef(), e.description(), false, e.createdAt()));
        }
        @Override public void enqueue(WebhookService.Delivery d) { deliveries.put(d.id(), d); }
        @Override public Optional<WebhookService.Delivery> delivery(TenantId t, UUID id) { return Optional.ofNullable(deliveries.get(id)); }
        @Override public void recordAttempt(TenantId t, UUID id, String status, int attempts, Instant next, Integer last, String error, Instant at) {
            var d = deliveries.get(id);
            deliveries.put(id, new WebhookService.Delivery(d.tenantId(), d.id(), d.endpointId(), d.event(), d.payload(), status, attempts, next, last, error, d.createdAt()));
        }
        @Override public List<WebhookService.Delivery> recentDeliveries(TenantId t, UUID e, int l) { return List.copyOf(deliveries.values()); }
    };

    private WebhookService service() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now; }
        };
        return new WebhookService(store, new WebhookService.Secrets() {
            @Override public void store(TenantId t, String ref, String value) { secrets.put(ref, value); }
            @Override public String read(String ref) { return secrets.get(ref); }
        }, (URI url, Map<String, String> headers, String body) -> {
            if (unreachable) throw new WebhookService.Sender.SendException("Connection failed");
            sentHeaders.add(headers);
            sentBodies.add(body);
            return nextStatus;
        }, e -> { }, clock);
    }

    @Test
    @DisplayName("registro: secreto una sola vez y cifrado aparte; eventos válidos; máximo 5 activos")
    void register() {
        var r = service().register(tenant, actor, "https://hooks.example.com/f", List.of("document.accepted"), "AdoptaFácil");
        assertTrue(r.secret().startsWith("whsec_"));
        assertEquals(r.secret(), secrets.get(r.endpoint().secretRef()));
        assertFalse(r.toString().contains(r.secret()), "el secreto no aparece en registros");
        assertEquals("INVALID_WEBHOOK_EVENT", assertThrows(DomainException.class,
                () -> service().register(tenant, actor, "https://hooks.example.com/f", List.of("ping"), null)).code());
        for (int i = 0; i < 4; i++) service().register(tenant, actor, "https://hooks.example.com/" + i, List.of("document.rejected"), null);
        assertEquals("WEBHOOK_LIMIT_REACHED", assertThrows(DomainException.class,
                () -> service().register(tenant, actor, "https://hooks.example.com/6", List.of("document.rejected"), null)).code());
    }

    @Test
    @DisplayName("entrega firmada; reintentos con espera creciente; tras 8 intentos queda fallida; solo a suscritos")
    void delivers() {
        WebhookService s = service();
        var r = s.register(tenant, actor, "https://hooks.example.com/f", List.of("document.accepted"), null);
        s.publish(tenant, WebhookEventType.DOCUMENT_REJECTED, "{}");
        assertTrue(deliveries.isEmpty(), "no está suscrito a rechazados");
        s.publish(tenant, WebhookEventType.DOCUMENT_ACCEPTED, "{\"documentId\":\"x\"}");
        UUID id = deliveries.keySet().iterator().next();

        s.deliver(tenant, id);
        assertEquals("DELIVERED", deliveries.get(id).status());
        Map<String, String> h = sentHeaders.getFirst();
        assertEquals("document.accepted", h.get("X-Billing-Event"));
        assertEquals(WebhookSignature.header(r.secret(), now.getEpochSecond(), sentBodies.getFirst()), h.get("X-Billing-Signature"));
        assertTrue(sentBodies.getFirst().contains("\"type\":\"document.accepted\"") && sentBodies.getFirst().contains("\"data\":{\"documentId\":\"x\"}"));
        s.deliver(tenant, id);
        assertEquals(1, sentBodies.size(), "una entregada no se reenvía");

        s.publish(tenant, WebhookEventType.DOCUMENT_ACCEPTED, "{}");
        UUID retry = deliveries.values().stream().filter(d -> d.status().equals("PENDING")).findFirst().orElseThrow().id();
        nextStatus = 500;
        s.deliver(tenant, retry);
        assertEquals("PENDING", deliveries.get(retry).status());
        assertEquals(now.plus(Duration.ofMinutes(1)), deliveries.get(retry).nextAttemptAt());
        assertEquals(Integer.valueOf(500), deliveries.get(retry).lastStatus());
        unreachable = true;
        for (int i = 2; i <= WebhookService.MAX_ATTEMPTS; i++) s.deliver(tenant, retry);
        assertEquals("FAILED", deliveries.get(retry).status());
        assertEquals(WebhookService.MAX_ATTEMPTS, deliveries.get(retry).attempts());
    }

    @Test
    @DisplayName("un endpoint desactivado no recibe nada nuevo y lo pendiente se descarta")
    void disabled() {
        WebhookService s = service();
        var r = s.register(tenant, actor, "https://hooks.example.com/f", List.of("document.failed"), null);
        s.publish(tenant, WebhookEventType.DOCUMENT_FAILED, "{}");
        s.disable(tenant, actor, r.endpoint().id());
        UUID id = deliveries.keySet().iterator().next();
        s.deliver(tenant, id);
        assertEquals("FAILED", deliveries.get(id).status());
        assertTrue(sentBodies.isEmpty());
        s.publish(tenant, WebhookEventType.DOCUMENT_FAILED, "{}");
        assertEquals(1, deliveries.size());
    }
}
