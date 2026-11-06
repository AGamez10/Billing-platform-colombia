package co.billingplatform.platform;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Imitación local de la API de links de pago de Bold para las pruebas de integración. Solo
 * acepta la llave de identidad {@link #IDENTITY_KEY}; cada link nuevo queda ACTIVE hasta que la
 * prueba le asigne otro estado con {@link #setStatus}.
 */
final class BoldStub {

    static final String IDENTITY_KEY = "stub-identity-key";
    private static final BoldStub INSTANCE = new BoldStub();

    private final HttpServer server;
    private final AtomicInteger links = new AtomicInteger();
    private final Map<String, String> statuses = new ConcurrentHashMap<>();

    private BoldStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String response;
            int code = 200;
            if (!("x-api-key " + IDENTITY_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                code = 401;
                response = "{\"errors\":[\"unauthorized\"]}";
            } else if (path.equals("/online/link/v1/payment_methods")) {
                response = "{\"payload\":{\"payment_methods\":[{\"name\":\"PSE\"},{\"name\":\"BRE_B\"}]},\"errors\":[]}";
            } else if (path.equals("/online/link/v1") && exchange.getRequestMethod().equals("POST")) {
                String id = "LNK_STUB" + links.incrementAndGet();
                statuses.put(id, "ACTIVE");
                response = "{\"payload\":{\"payment_link\":\"%s\",\"url\":\"https://checkout.bold.co/%s\"},\"errors\":[]}"
                        .formatted(id, id);
            } else if (path.startsWith("/online/link/v1/") && statuses.containsKey(path.substring(16))) {
                String status = statuses.get(path.substring(16));
                response = status.equals("PAID")
                        ? "{\"payload\":{\"status\":\"PAID\",\"transaction_id\":\"TX-%s\",\"payment_method\":\"BRE_B\"}}"
                                .formatted(path.substring(16))
                        : "{\"payload\":{\"status\":\"%s\"}}".formatted(status);
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

    static BoldStub get() {
        return INSTANCE;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int linksCreated() {
        return links.get();
    }

    void setStatus(String linkId, String status) {
        statuses.put(linkId, status);
    }
}
