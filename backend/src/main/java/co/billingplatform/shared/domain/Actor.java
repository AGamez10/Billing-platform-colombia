package co.billingplatform.shared.domain;

import java.util.UUID;

/**
 * Quién ejecuta una operación: un usuario o una clave de API (integración). Lo construye la
 * capa web a partir del principal autenticado; se usa para auditoría.
 */
public record Actor(UUID userId, UUID apiKeyId) {

    public Actor {
        if ((userId == null) == (apiKeyId == null)) {
            throw new IllegalArgumentException("an actor is either a user or an API key");
        }
    }

    public static Actor user(UUID userId) {
        return new Actor(userId, null);
    }

    public static Actor apiKey(UUID apiKeyId) {
        return new Actor(null, apiKeyId);
    }
}
