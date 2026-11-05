package co.billingplatform.shared.domain;

import java.util.List;

/** Página de resultados. {@code hasMore} se calcula pidiendo un elemento de más. */
public record Page<T>(List<T> items, int limit, int offset, boolean hasMore) {

    public static final int MAX_LIMIT = 100;

    public Page {
        items = List.copyOf(items);
    }

    public static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return 20;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    public static int clampOffset(Integer requested) {
        return requested == null || requested < 0 ? 0 : Math.min(requested, 100_000);
    }

    /** Construye la página a partir de una consulta que pidió {@code limit + 1} filas. */
    public static <T> Page<T> of(List<T> fetched, int limit, int offset) {
        boolean more = fetched.size() > limit;
        return new Page<>(more ? fetched.subList(0, limit) : fetched, limit, offset, more);
    }
}
