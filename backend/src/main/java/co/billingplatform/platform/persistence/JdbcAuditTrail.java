package co.billingplatform.platform.persistence;

import co.billingplatform.shared.application.AuditTrail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.stream.Collectors;

/** Inserta en {@code audit_log} dentro de la transacción del caso de uso (si este falla, no queda registro). */
@Repository
class JdbcAuditTrail implements AuditTrail {

    private final JdbcClient jdbc;

    JdbcAuditTrail(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Event e) {
        jdbc.sql("""
                        INSERT INTO audit_log (tenant_id, actor_user_id, actor_api_key, action, resource_type, resource_id, details)
                        VALUES (:t, :u, :k, :action, :type, :rid, CAST(:details AS jsonb))
                        """)
                .param("t", e.tenantId().value())
                .param("u", e.actor().userId())
                .param("k", e.actor().apiKeyId())
                .param("action", e.action())
                .param("type", e.resourceType())
                .param("rid", e.resourceId())
                .param("details", toJson(e.details()))
                .update();
    }

    /** JSON plano de texto a texto, escapado a mano para no depender del ObjectMapper aquí. */
    static String toJson(Map<String, String> details) {
        return details.entrySet().stream()
                .map(en -> quote(en.getKey()) + ":" + quote(en.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
