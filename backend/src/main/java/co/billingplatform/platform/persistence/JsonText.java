package co.billingplatform.platform.persistence;

import java.util.Map;
import java.util.stream.Collectors;

/** JSON mínimo para columnas jsonb (objetos planos y arreglos de texto), con escape correcto. */
public final class JsonText {

    private JsonText() {}

    /** Objeto plano; valores String, Number, Boolean o null. */
    public static String object(Map<String, ?> fields) {
        return fields.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + value(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static String stringArray(Iterable<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (String v : values) {
            if (b.length() > 1) b.append(',');
            b.append(quote(v));
        }
        return b.append(']').toString();
    }

    private static String value(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return quote(v.toString());
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
