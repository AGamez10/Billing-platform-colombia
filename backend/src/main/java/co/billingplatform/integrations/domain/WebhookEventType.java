package co.billingplatform.integrations.domain;

import co.billingplatform.shared.domain.DomainException;

/** Eventos que se pueden suscribir. El nombre público es estable (contrato con integradores). */
public enum WebhookEventType {
    DOCUMENT_ACCEPTED("document.accepted"),
    DOCUMENT_REJECTED("document.rejected"),
    DOCUMENT_FAILED("document.failed"),
    /** Solo al probar un endpoint. */
    PING("ping");

    private final String wireName;

    WebhookEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static WebhookEventType ofWire(String name) {
        for (WebhookEventType t : values()) {
            if (t.wireName.equals(name) && t != PING) {
                return t;
            }
        }
        throw new DomainException("INVALID_WEBHOOK_EVENT", "Unknown event: " + name);
    }
}
