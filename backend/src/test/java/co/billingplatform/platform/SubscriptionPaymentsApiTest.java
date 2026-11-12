package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pago de planes con Mercado Pago, contra una imitación local de su API. */
class SubscriptionPaymentsApiTest extends ApiTest {

    private static Map<String, String> signed(String dataId) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String requestId = UUID.randomUUID().toString();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(MercadoPagoStub.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        String v1 = HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        return Map.of("x-signature", "ts=" + ts + ",v1=" + v1, "x-request-id", requestId);
    }

    private HttpResponse<String> webhook(String paymentId, Map<String, String> headers) throws Exception {
        return send("POST", "/api/v1/webhooks/mercadopago?data.id=" + paymentId + "&type=payment", null,
                "{\"type\":\"payment\",\"data\":{\"id\":\"" + paymentId + "\"}}", headers);
    }

    @Test
    @DisplayName("pagar un plan: preferencia → pago aprobado avisado con firma → plan activo, una sola vez")
    void payPlan() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> sub = send("GET", "/api/v1/subscription", token, null);
        assertEquals(200, sub.statusCode(), sub.body());
        assertEquals("TRIAL", field(sub.body(), "status"));
        assertEquals("true", field(sub.body(), "onlinePayments"));

        HttpResponse<String> started = send("POST", "/api/v1/subscription/checkouts", token,
                "{\"planCode\":\"EMPRENDEDOR\",\"cycle\":\"MONTHLY\"}");
        assertEquals(201, started.statusCode(), started.body());
        String checkoutId = field(started.body(), "id");
        assertEquals("12900.00", field(started.body(), "amount"));
        assertTrue(field(started.body(), "checkoutUrl").startsWith("https://www.mercadopago.com.co/"));
        String preference = MercadoPagoStub.get().lastPreferenceBody();
        assertTrue(preference.contains("\"external_reference\":\"" + checkoutId + "\""), preference);
        assertTrue(preference.contains("\"unit_price\":12900"), "COP sin decimales: " + preference);
        assertTrue(preference.contains("/api/v1/webhooks/mercadopago"), preference);

        assertEquals("PENDING", field(send("GET", "/api/v1/subscription/checkouts/" + checkoutId, token, null).body(), "status"));

        String rejected = MercadoPagoStub.get().pay(checkoutId, "rejected", "12900", false);
        assertEquals(200, webhook(rejected, signed(rejected)).statusCode());
        HttpResponse<String> afterRejected = send("GET", "/api/v1/subscription/checkouts/" + checkoutId, token, null);
        assertEquals("PENDING", field(afterRejected.body(), "status"), "un intento rechazado no activa nada");
        assertEquals("REJECTED", field(afterRejected.body(), "lastAttempt"));

        String paymentId = MercadoPagoStub.get().pay(checkoutId, "approved", "12900", false);
        assertEquals(401, webhook(paymentId, Map.of("x-signature", "ts=1,v1=" + "0".repeat(64), "x-request-id", "x")).statusCode(),
                "sin firma válida no se procesa");
        HttpResponse<String> hook = webhook(paymentId, signed(paymentId));
        assertEquals(200, hook.statusCode(), hook.body());
        assertEquals("PAID", field(hook.body(), "status"));
        assertEquals(200, webhook(paymentId, signed(paymentId)).statusCode(), "avisos repetidos se aceptan sin efecto");

        HttpResponse<String> active = send("GET", "/api/v1/subscription", token, null);
        assertEquals("ACTIVE", field(active.body(), "status"));
        assertEquals("EMPRENDEDOR", field(active.body(), "planCode"));
        LocalDate end = LocalDate.parse(field(active.body(), "periodEnd"));
        LocalDate start = LocalDate.parse(field(active.body(), "periodStart"));
        assertEquals(start.plusMonths(1), end);

        // Renovar el mismo plan al día extiende desde el fin del periodo: no se pierden días.
        String renewal = field(send("POST", "/api/v1/subscription/checkouts", token,
                "{\"planCode\":\"EMPRENDEDOR\",\"cycle\":\"MONTHLY\"}").body(), "id");
        String renewalPayment = MercadoPagoStub.get().pay(renewal, "approved", "12900", false);
        assertEquals("PAID", field(send("GET", "/api/v1/subscription/checkouts/" + renewal, token, null).body(), "status"),
                "al volver de Mercado Pago, la consulta activa el pago aunque el aviso no haya llegado");
        assertNotNull(renewalPayment);
        assertEquals(end.plusMonths(1).toString(), field(send("GET", "/api/v1/subscription", token, null).body(), "periodEnd"));

        assertEquals(404, send("GET", "/api/v1/subscription/checkouts/" + checkoutId, registerOrganization(), null).statusCode(),
                "otra organización no ve el pago");
    }

    @Test
    @DisplayName("un pago por otro valor o de otro checkout nunca activa el plan; Micro no se vende mensual")
    void invalidPayments() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> micro = send("POST", "/api/v1/subscription/checkouts", token, "{\"planCode\":\"MICRO\",\"cycle\":\"MONTHLY\"}");
        assertEquals("PLAN_CYCLE_NOT_AVAILABLE", field(micro.body(), "code"));
        assertEquals(401, send("POST", "/api/v1/subscription/checkouts", null, "{\"planCode\":\"PRO\",\"cycle\":\"MONTHLY\"}").statusCode());

        String checkoutId = field(send("POST", "/api/v1/subscription/checkouts", token,
                "{\"planCode\":\"PRO\",\"cycle\":\"MONTHLY\"}").body(), "id");
        String cheap = MercadoPagoStub.get().pay(checkoutId, "approved", "1000", false);
        assertEquals(200, webhook(cheap, signed(cheap)).statusCode());
        assertEquals("PENDING", field(send("GET", "/api/v1/subscription/checkouts/" + checkoutId, token, null).body(), "status"),
                "un pago por menos no activa el plan");
        String foreign = MercadoPagoStub.get().pay("otra-referencia", "approved", "69900", false);
        assertEquals(200, webhook(foreign, signed(foreign)).statusCode(), "pagos ajenos se confirman y se ignoran");
        assertEquals("TRIAL", field(send("GET", "/api/v1/subscription", token, null).body(), "status"));
    }
}
