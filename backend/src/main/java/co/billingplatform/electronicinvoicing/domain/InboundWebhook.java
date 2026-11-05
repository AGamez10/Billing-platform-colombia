package co.billingplatform.electronicinvoicing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Petición de webhook cruda, aún no verificada. */
public final class InboundWebhook {

    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Instant receivedAt;

    public InboundWebhook(Map<String, List<String>> headers, byte[] body, Instant receivedAt) {
        Map<String, List<String>> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Objects.requireNonNull(headers).forEach((k, v) -> normalized.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        this.headers = normalized;
        this.body = Objects.requireNonNull(body).clone();
        this.receivedAt = Objects.requireNonNull(receivedAt);
    }

    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.size() != 1 ? Optional.empty() : Optional.of(values.getFirst());
    }

    public byte[] body() {
        return body.clone();
    }

    public Instant receivedAt() {
        return receivedAt;
    }
}
