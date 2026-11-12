package co.billingplatform.platform;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Imitación local de la API de Mercado Pago (preferencias y pagos) para las pruebas de integración. */
final class MercadoPagoStub {

    static final String ACCESS_TOKEN = "TEST-stub-access-token";
    static final String WEBHOOK_SECRET = "stub-webhook-secret";
    private static final MercadoPagoStub INSTANCE = new MercadoPagoStub();

    record StubPayment(String id, String status, String externalReference, String amount, String currency, boolean liveMode) {
        String json() {
            return "{\"id\":%s,\"status\":\"%s\",\"external_reference\":\"%s\",\"transaction_amount\":%s,\"currency_id\":\"%s\",\"live_mode\":%s}"
                    .formatted(id, status, externalReference, amount, currency, liveMode);
        }
    }

    private final HttpServer server;
    private final AtomicInteger counter = new AtomicInteger(1000);
    private final Map<String, StubPayment> payments = new ConcurrentHashMap<>();
    private final List<String> preferenceBodies = new CopyOnWriteArrayList<>();

    private MercadoPagoStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            int code = 200;
            String response;
            if (!("Bearer " + ACCESS_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                code = 401;
                response = "{\"message\":\"invalid token\"}";
            } else if (path.equals("/checkout/preferences") && exchange.getRequestMethod().equals("POST")) {
                preferenceBodies.add(body);
                String id = "pref-" + counter.incrementAndGet();
                code = 201;
                response = "{\"id\":\"%s\",\"init_point\":\"https://www.mercadopago.com.co/checkout/v1/redirect?pref_id=%s\"}".formatted(id, id);
            } else if (path.equals("/v1/payments/search")) {
                String ref = param(query, "external_reference");
                StringBuilder results = new StringBuilder();
                payments.values().stream().filter(p -> p.externalReference().equals(ref)).forEach(p -> {
                    if (!results.isEmpty()) results.append(',');
                    results.append(p.json());
                });
                response = "{\"results\":[" + results + "]}";
            } else if (path.startsWith("/v1/payments/") && payments.containsKey(path.substring(13))) {
                response = payments.get(path.substring(13)).json();
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
    }

    private static String param(String query, String name) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals(name) && kv.length == 2) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return "";
    }

    static MercadoPagoStub get() {
        return INSTANCE;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Registra un pago del cliente y devuelve su id. */
    String pay(String externalReference, String status, String amount, boolean liveMode) {
        String id = String.valueOf(counter.incrementAndGet());
        payments.put(id, new StubPayment(id, status, externalReference, amount, "COP", liveMode));
        return id;
    }

    String lastPreferenceBody() {
        return preferenceBodies.isEmpty() ? "" : preferenceBodies.getLast();
    }
}
