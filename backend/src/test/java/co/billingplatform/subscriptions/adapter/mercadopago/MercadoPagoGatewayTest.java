package co.billingplatform.subscriptions.adapter.mercadopago;

import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoGatewayTest {

    private HttpServer server;
    private MercadoPagoGateway gateway;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            String json;
            int code = 200;
            if (path.equals("/checkout/preferences")) {
                json = "{\"id\":\"123-abc\",\"init_point\":\"https://www.mercadopago.com.co/checkout/v1/redirect?pref_id=123-abc\"}";
                code = 201;
            } else if (path.equals("/v1/payments/search")) {
                json = "{\"results\":[{\"id\":555,\"status\":\"approved\",\"external_reference\":\"ref-1\",\"transaction_amount\":12900,\"currency_id\":\"COP\",\"live_mode\":true}]}";
            } else if (path.equals("/v1/payments/555")) {
                json = "{\"id\":555,\"status\":\"in_process\",\"external_reference\":\"ref-1\",\"transaction_amount\":12900.0,\"currency_id\":\"COP\",\"live_mode\":false}";
            } else {
                code = 404;
                json = "{}";
            }
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        gateway = new MercadoPagoGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "TEST-token", "secreto",
                Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("crea la preferencia con referencia, valor en pesos enteros, URLs y vencimiento")
    void preference() {
        PaymentGateway.Preference p = gateway.createPreference(new PaymentGateway.Request("ref-1", "Plan \"Pro\" · pago mensual",
                new BigDecimal("12900.00"), URI.create("https://app.co/api/v1/webhooks/mercadopago"), URI.create("https://app.co/plan?pago=1"),
                URI.create("https://app.co/plan?pago=1"), URI.create("https://app.co/plan?pago=1"), Instant.parse("2026-11-14T15:00:00Z")));
        assertEquals("123-abc", p.id());
        assertTrue(p.checkoutUrl().startsWith("https://"));
        String body = lastBody.get();
        assertTrue(body.contains("\"unit_price\":12900"), body);
        assertTrue(body.contains("\"external_reference\":\"ref-1\""), body);
        assertTrue(body.contains("Plan \\\"Pro\\\""), "el título se escapa en JSON: " + body);
        assertTrue(body.contains("\"expiration_date_to\":\"2026-11-14T15:00:00.000Z\""), body);
        assertEquals("Bearer TEST-token", lastAuth.get());
    }

    @Test
    @DisplayName("lee pagos por id y por referencia, y traduce los estados")
    void payments() {
        List<PaymentGateway.Payment> found = gateway.paymentsFor("ref-1");
        assertEquals(1, found.size());
        assertEquals(PaymentGateway.PaymentStatus.APPROVED, found.getFirst().status());
        assertEquals(0, found.getFirst().amount().compareTo(new BigDecimal("12900")));
        assertTrue(found.getFirst().liveMode());
        PaymentGateway.Payment byId = gateway.payment("555").orElseThrow();
        assertEquals(PaymentGateway.PaymentStatus.PENDING, byId.status());
        assertEquals("555", byId.id());
        assertFalse(byId.liveMode());
        assertTrue(gateway.payment("999").isEmpty());
        assertTrue(gateway.payment("../etc").isEmpty(), "ids no numéricos no se consultan");
        assertEquals(PaymentGateway.PaymentStatus.REFUNDED, MercadoPagoGateway.map("charged_back"));
        assertEquals(PaymentGateway.PaymentStatus.REJECTED, MercadoPagoGateway.map("cancelled"));
        assertEquals(PaymentGateway.PaymentStatus.PENDING, MercadoPagoGateway.map("algo-nuevo"));
    }

    @Test
    @DisplayName("verifica la firma x-signature con el manifiesto id;request-id;ts")
    void signature() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secreto".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal("id:555;request-id:req-1;ts:1700000000;".getBytes(StandardCharsets.UTF_8)));
        assertTrue(gateway.verifyWebhook("555", "req-1", "ts=1700000000,v1=" + v1));
        assertTrue(gateway.verifyWebhook("555", "req-1", " ts=1700000000 , v1=" + v1.toUpperCase()));
        assertFalse(gateway.verifyWebhook("556", "req-1", "ts=1700000000,v1=" + v1), "otro pago");
        assertFalse(gateway.verifyWebhook("555", "req-2", "ts=1700000000,v1=" + v1), "otra petición");
        assertFalse(gateway.verifyWebhook("555", "req-1", null));
        assertFalse(gateway.verifyWebhook("555", "req-1", "v1=" + v1));
        MercadoPagoGateway noSecret = new MercadoPagoGateway(URI.create("http://127.0.0.1"), "t", "", Duration.ofSeconds(1));
        assertFalse(noSecret.verifyWebhook("555", "req-1", "ts=1700000000,v1=" + v1), "sin clave configurada no se acepta ningún aviso");
    }
}
