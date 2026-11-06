package co.billingplatform.payments.adapter.bold;

import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.payments.port.MerchantPaymentGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contra un servidor HTTP local que imita las respuestas documentadas de Bold. */
class BoldGatewayTest {

    private HttpServer server;
    private BoldGateway bold;
    private final List<String> requests = new ArrayList<>();
    private final MerchantPaymentGateway.Credentials test =
            new MerchantPaymentGateway.Credentials(PaymentAccount.Mode.TEST, "id-key-test", "");
    private volatile String statusBody = "{\"payload\":{\"status\":\"ACTIVE\"}}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + auth + " " + body);
            String path = exchange.getRequestURI().getPath();
            String response;
            int code = 200;
            if (!"x-api-key id-key-test".equals(auth)) {
                code = 401;
                response = "{\"errors\":[\"unauthorized\"]}";
            } else if (path.equals("/online/link/v1/payment_methods")) {
                response = "{\"payload\":{\"payment_methods\":[{\"name\":\"PSE\"},{\"name\":\"NEQUI\"},{\"name\":\"BRE_B\"}]},\"errors\":[]}";
            } else if (path.equals("/online/link/v1") && exchange.getRequestMethod().equals("POST")) {
                response = "{\"payload\":{\"payment_link\":\"LNK_ABC123\",\"url\":\"https://checkout.bold.co/LNK_ABC123\"},\"errors\":[]}";
            } else if (path.equals("/online/link/v1/LNK_ABC123")) {
                response = statusBody;
            } else {
                code = 404;
                response = "{}";
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        bold = new BoldGateway(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("verifica las llaves y lista los medios de pago habilitados (para confirmar Bre-B)")
    void verifiesCredentials() {
        assertEquals(List.of("PSE", "NEQUI", "BRE_B"), bold.verifyCredentials(test));
        MerchantPaymentGateway.GatewayException e = assertThrows(MerchantPaymentGateway.GatewayException.class,
                () -> bold.verifyCredentials(new MerchantPaymentGateway.Credentials(PaymentAccount.Mode.TEST, "otra", "")));
        assertTrue(e.credentialsRejected(), "un 401 se reporta como llaves rechazadas");
    }

    @Test
    @DisplayName("crea el link por el total en pesos enteros con nuestra referencia, y lee el estado")
    void createsLinkAndReadsStatus() {
        MerchantPaymentGateway.CreatedLink link = bold.createLink(test, "ref-123", new BigDecimal("5950.00"), "Venta \"1\"");
        assertEquals("LNK_ABC123", link.linkId());
        assertEquals("https://checkout.bold.co/LNK_ABC123", link.checkoutUrl());
        String sent = requests.get(requests.size() - 1);
        assertTrue(sent.contains("\"amount_type\":\"CLOSE\""), sent);
        assertTrue(sent.contains("\"total_amount\":5950,"), sent);
        assertTrue(sent.contains("\"reference\":\"ref-123\""), sent);
        assertTrue(sent.contains("\"description\":\"Venta \\\"1\\\"\""), "el texto va escapado: " + sent);

        assertEquals(Charge.Status.PENDING, bold.status(test, "LNK_ABC123").status());
        statusBody = "{\"payload\":{\"status\":\"PAID\",\"payment_method\":\"BRE_B\",\"transaction_id\":\"TX9\",\"total\":5950}}";
        MerchantPaymentGateway.LinkStatus paid = bold.status(test, "LNK_ABC123");
        assertEquals(Charge.Status.PAID, paid.status());
        assertEquals("BRE_B", paid.paymentMethod());
        assertEquals("TX9", paid.transactionId());
    }

    @Test
    @DisplayName("estados de Bold: solo PAID aprueba; ACTIVE y PROCESSING siguen pendientes")
    void mapsStatuses() {
        assertEquals(Charge.Status.PENDING, BoldGateway.map("ACTIVE"));
        assertEquals(Charge.Status.PENDING, BoldGateway.map("PROCESSING"));
        assertEquals(Charge.Status.PENDING, BoldGateway.map(null));
        assertEquals(Charge.Status.PAID, BoldGateway.map("PAID"));
        assertEquals(Charge.Status.REJECTED, BoldGateway.map("REJECTED"));
        assertEquals(Charge.Status.EXPIRED, BoldGateway.map("EXPIRED"));
        assertEquals(Charge.Status.CANCELLED, BoldGateway.map("CANCELLED"));
    }

    @Test
    @DisplayName("firma del webhook: HMAC-SHA256 del cuerpo en Base64; llave vacía en pruebas")
    void verifiesWebhookSignature() throws Exception {
        byte[] body = "{\"type\":\"SALE_APPROVED\"}".getBytes(StandardCharsets.UTF_8);
        String live = hmac("secreta-viva", body);
        MerchantPaymentGateway.Credentials liveCreds = new MerchantPaymentGateway.Credentials(PaymentAccount.Mode.LIVE, "id", "secreta-viva");
        assertTrue(bold.verifyWebhook(liveCreds, body, live));
        assertTrue(bold.verifyWebhook(liveCreds, body, live.toUpperCase()), "hexadecimal sin importar mayúsculas");
        assertFalse(bold.verifyWebhook(liveCreds, "{\"type\":\"SALE_REJECTED\"}".getBytes(StandardCharsets.UTF_8), live), "cuerpo alterado");
        assertFalse(bold.verifyWebhook(liveCreds, body, null));
        assertTrue(bold.verifyWebhook(test, body, hmacEmptyKey(body)), "modo pruebas: llave vacía");
    }

    @Test
    @DisplayName("JSON: escapes, números exactos y rechazo de basura")
    void parsesJsonStrictly() {
        Object v = BoldJson.parse("{\"a\":[1,2.50,{\"b\":\"x\\\"y\\u00e9\"}],\"c\":true,\"d\":null}");
        assertEquals("x\"yé", BoldJson.text(((java.util.List<?>) BoldJson.path(v, "a")).get(2), "b"));
        assertEquals("2.50", ((java.util.List<?>) BoldJson.path(v, "a")).get(1).toString());
        assertThrows(IllegalArgumentException.class, () -> BoldJson.parse("{\"a\":1} extra"));
        assertThrows(IllegalArgumentException.class, () -> BoldJson.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> BoldJson.parse("[".repeat(100) + "]".repeat(100)));
    }

    private static String hmac(String key, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(Base64.getEncoder().encode(body)));
    }

    /** HMAC con llave vacía según RFC 2104: la llave se completa con ceros hasta el bloque. */
    private static String hmacEmptyKey(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[64], "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(Base64.getEncoder().encode(body)));
    }
}
