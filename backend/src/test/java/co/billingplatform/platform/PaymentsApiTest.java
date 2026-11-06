package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Cobra y la factura sale sola" por HTTP real, contra una imitación local de Bold: conectar la
 * cuenta del comercio, cobrar, confirmar por webhook firmado y ver el POS emitido solo.
 */
class PaymentsApiTest extends ApiTest {

    private static final String SALE = """
            {"lines":[{"description":"Gaseosa","unitCode":"94","quantity":"2","unitPrice":"2500","taxRateCodes":["IVA_19"]}]}""";

    private void setUpPosIssuer(String token) throws Exception {
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        LocalDate today = LocalDate.now();
        HttpResponse<String> range = send("POST", "/api/v1/numbering-ranges", token,
                ("{\"kind\":\"POS_EQUIVALENT\",\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000009\",\"prefix\":\"CAJA\","
                        + "\"rangeFrom\":1,\"rangeTo\":1000,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(today.minusDays(1), today.plusYears(1)));
        assertEquals(201, range.statusCode(), range.body());
    }

    private HttpResponse<String> connect(String token, String mode, String identity) throws Exception {
        return send("POST", "/api/v1/payment-accounts", token,
                "{\"mode\":\"%s\",\"identityKey\":\"%s\",\"secretKey\":\"%s\"}".formatted(mode, identity,
                        mode.equals("LIVE") ? "llave-secreta-viva" : ""));
    }

    private HttpResponse<String> charge(String token, String key) throws Exception {
        return send("POST", "/api/v1/pos-charges", token, SALE, Map.of("Idempotency-Key", key));
    }

    /** Firma de Bold: HMAC-SHA256 (hex) del cuerpo en Base64; en pruebas la llave es vacía. */
    private static String boldSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[] {0}, "HmacSHA256")); // equivale a la llave vacía (RFC 2104)
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> awaitAccepted(String token, String invoiceId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        HttpResponse<String> res;
        do {
            res = send("GET", "/api/v1/invoices/" + invoiceId, token, null);
            if (field(res.body(), "status").equals("ACCEPTED")) {
                return res;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        fail("el POS no fue aceptado: " + res.body());
        return res;
    }

    @Test
    @DisplayName("conectar Bold → cobrar → webhook firmado → el POS se emite solo, una sola vez")
    void chargeAndIssue() throws Exception {
        String token = registerOrganization();
        setUpPosIssuer(token);

        HttpResponse<String> noAccount = charge(token, "cobro-" + UUID.randomUUID());
        assertEquals(409, noAccount.statusCode(), noAccount.body());
        assertEquals("PAYMENT_ACCOUNT_REQUIRED", field(noAccount.body(), "code"));

        HttpResponse<String> badKeys = connect(token, "TEST", "llave-equivocada");
        assertEquals(422, badKeys.statusCode(), badKeys.body());
        assertEquals("PAYMENT_CREDENTIALS_REJECTED", field(badKeys.body(), "code"));

        HttpResponse<String> connected = connect(token, "TEST", BoldStub.IDENTITY_KEY);
        assertEquals(201, connected.statusCode(), connected.body());
        assertTrue(connected.body().contains("BRE_B"), "muestra los medios habilitados para confirmar Bre-B");
        assertFalse(connected.body().contains(BoldStub.IDENTITY_KEY), "las llaves nunca se devuelven");
        String webhookPath = field(connected.body(), "webhookPath");
        assertTrue(webhookPath.startsWith("/api/v1/webhooks/bold/"), webhookPath);

        HttpResponse<String> current = send("GET", "/api/v1/payment-accounts", token, null);
        assertEquals("true", field(current.body(), "connected"));
        assertEquals("TEST", field(current.body(), "mode"));
        assertFalse(current.body().contains("webhookPath"), "la ruta del webhook solo se muestra al conectar");

        String key = "cobro-" + UUID.randomUUID();
        HttpResponse<String> started = charge(token, key);
        assertEquals(201, started.statusCode(), started.body());
        String chargeId = field(started.body(), "id");
        assertEquals("PENDING", field(started.body(), "status"));
        assertEquals("5950.00", field(started.body(), "amount"));
        assertEquals("true", field(started.body(), "test"));
        String checkout = field(started.body(), "checkoutUrl");
        assertTrue(checkout.startsWith("https://checkout.bold.co/LNK_STUB"), checkout);
        assertEquals("POS_EQUIVALENT", field(started.body(), "kind"));
        assertFalse(started.body().contains("\"number\""), "sin pago, el POS sigue en borrador y no consume número");

        int linksBefore = BoldStub.get().linksCreated();
        HttpResponse<String> retry = charge(token, key);
        assertEquals(chargeId, field(retry.body(), "id"), "reintentar con la misma clave devuelve el mismo cobro");
        assertEquals(linksBefore, BoldStub.get().linksCreated(), "y no crea otro link de pago");

        assertEquals("PENDING", field(send("GET", "/api/v1/pos-charges/" + chargeId, token, null).body(), "status"));

        String other = registerOrganization();
        assertEquals(404, send("GET", "/api/v1/pos-charges/" + chargeId, other, null).statusCode(),
                "otra organización no ve el cobro");

        // El cliente paga en Bold; llega la notificación.
        String linkId = checkout.substring(checkout.lastIndexOf('/') + 1);
        BoldStub.get().setStatus(linkId, "PAID");
        String notification = "{\"type\":\"SALE_APPROVED\",\"data\":{\"metadata\":{\"reference\":\"" + chargeId + "\"}}}";

        HttpResponse<String> forged = send("POST", webhookPath, null, notification, Map.of("x-bold-signature", "00ff"));
        assertEquals(401, forged.statusCode(), "sin firma válida no se procesa");
        assertEquals(404, send("POST", "/api/v1/webhooks/bold/token-inexistente", null, notification,
                Map.of("x-bold-signature", boldSignature(notification))).statusCode());

        HttpResponse<String> hook = send("POST", webhookPath, null, notification,
                Map.of("x-bold-signature", boldSignature(notification)));
        assertEquals(200, hook.statusCode(), hook.body());

        HttpResponse<String> paid = send("GET", "/api/v1/pos-charges/" + chargeId, token, null);
        assertEquals("PAID", field(paid.body(), "status"), paid.body());
        assertEquals("BRE_B", field(paid.body(), "paymentMethod"));
        assertFalse(paid.body().contains("checkoutUrl"), "un cobro pagado ya no ofrece el link");
        assertEquals("CAJA1", field(paid.body(), "number"), "el pago emitió el POS");
        String invoiceId = field(paid.body().substring(paid.body().indexOf("\"sale\"")), "id");

        HttpResponse<String> again = send("POST", webhookPath, null, notification,
                Map.of("x-bold-signature", boldSignature(notification)));
        assertEquals(200, again.statusCode(), "las notificaciones repetidas se aceptan");
        assertEquals("CAJA1", field(send("GET", "/api/v1/pos-charges/" + chargeId, token, null).body(), "number"),
                "y no emiten otra vez");

        HttpResponse<String> done = awaitAccepted(token, invoiceId);
        assertEquals("false", field(done.body(), "legallyValid"), "pago de prueba + simulación: sin validez tributaria");

        assertEquals(409, send("DELETE", "/api/v1/invoices/" + invoiceId, token, null).statusCode());
    }

    @Test
    @DisplayName("antes de cobrar se verifica que el documento podrá salir; cobro real solo con facturación en producción")
    void preconditions() throws Exception {
        String token = registerOrganization();
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        assertEquals(201, connect(token, "TEST", BoldStub.IDENTITY_KEY).statusCode());

        int links = BoldStub.get().linksCreated();
        HttpResponse<String> noRange = charge(token, "cobro-" + UUID.randomUUID());
        assertEquals(409, noRange.statusCode(), noRange.body());
        assertEquals("NUMBERING_RANGE_NOT_FOUND", field(noRange.body(), "code"));
        assertEquals(links, BoldStub.get().linksCreated(), "sin resolución POS no se le cobra al cliente");

        HttpResponse<String> live = connect(token, "LIVE", BoldStub.IDENTITY_KEY);
        assertEquals(201, live.statusCode(), live.body());
        assertEquals("LIVE", field(send("GET", "/api/v1/payment-accounts", token, null).body(), "mode"),
                "la cuenta nueva reemplaza a la anterior");
        LocalDate today = LocalDate.now();
        send("POST", "/api/v1/numbering-ranges", token,
                ("{\"kind\":\"POS_EQUIVALENT\",\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000010\",\"prefix\":\"P\","
                        + "\"rangeFrom\":1,\"rangeTo\":10,\"validFrom\":\"%s\",\"validTo\":\"%s\"}").formatted(today, today.plusDays(30)));
        HttpResponse<String> mismatch = charge(token, "cobro-" + UUID.randomUUID());
        assertEquals(409, mismatch.statusCode(), mismatch.body());
        assertEquals("PAYMENT_ENVIRONMENT_MISMATCH", field(mismatch.body(), "code"),
                "dinero real nunca con un documento simulado");

        HttpResponse<String> noSecret = send("POST", "/api/v1/payment-accounts", token,
                "{\"mode\":\"LIVE\",\"identityKey\":\"" + BoldStub.IDENTITY_KEY + "\"}");
        assertEquals("PAYMENT_CREDENTIALS_INVALID", field(noSecret.body(), "code"));
    }

    @Test
    @DisplayName("si el borrador se edita después de cobrar, el pago no documenta un valor distinto al cobrado")
    void editedDraftIsNotIssued() throws Exception {
        String token = registerOrganization();
        setUpPosIssuer(token);
        assertEquals(201, connect(token, "TEST", BoldStub.IDENTITY_KEY).statusCode());
        HttpResponse<String> started = charge(token, "cobro-" + UUID.randomUUID());
        assertEquals(201, started.statusCode(), started.body());
        String chargeId = field(started.body(), "id");
        String invoiceId = field(started.body().substring(started.body().indexOf("\"sale\"")), "id");

        String customerId = field(send("POST", "/api/v1/customers", token,
                "{" + randomNitJson() + ",\"legalName\":\"Cliente Caja\"}").body(), "id");
        HttpResponse<String> edited = send("PUT", "/api/v1/invoices/" + invoiceId, token,
                SALE.replace("2500", "9000").replace("{\"lines\"", "{\"customerId\":\"" + customerId + "\",\"lines\""));
        assertEquals(200, edited.statusCode(), edited.body());

        String checkout = field(started.body(), "checkoutUrl");
        BoldStub.get().setStatus(checkout.substring(checkout.lastIndexOf('/') + 1), "PAID");
        HttpResponse<String> paid = send("GET", "/api/v1/pos-charges/" + chargeId, token, null);
        assertEquals("PAID", field(paid.body(), "status"), paid.body());
        assertEquals("CHARGE_AMOUNT_MISMATCH", field(paid.body(), "issueBlockedCode"));
        assertFalse(paid.body().contains("\"number\""), "el documento no se emitió solo");
    }
}
