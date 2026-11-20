package co.billingplatform.insights.domain;

/**
 * Algo que la organización debe atender, con el siguiente paso concreto. Lo ven en el inicio y,
 * los importantes, también llegan por correo (avisos automáticos).
 *
 * @param key identifica la situación (código + objeto) para no repetir el mismo aviso
 */
public record Attention(String code, Severity severity, String title, String detail, String actionLabel, String actionPath,
                        String key) {

    public enum Severity { INFO, WARNING, CRITICAL }
}
