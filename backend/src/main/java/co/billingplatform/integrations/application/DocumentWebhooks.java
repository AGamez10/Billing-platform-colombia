package co.billingplatform.integrations.application;

import co.billingplatform.integrations.domain.WebhookEventType;
import co.billingplatform.invoicing.application.InvoiceEvents;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.json.Json;

import java.util.Objects;

/**
 * Traduce los estados finales de un documento al aviso público {@code document.*}. El cuerpo
 * lleva lo necesario para conciliar (id, tipo, número, estado, ambiente, CUFE/CUDE, total,
 * identificación del comprador) y nada más.
 */
public final class DocumentWebhooks implements InvoiceEvents {

    private final WebhookService webhooks;

    public DocumentWebhooks(WebhookService webhooks) {
        this.webhooks = Objects.requireNonNull(webhooks);
    }

    @Override
    public void reachedFinalStatus(Invoice i) {
        WebhookEventType type = switch (i.status()) {
            case ACCEPTED -> WebhookEventType.DOCUMENT_ACCEPTED;
            case REJECTED -> WebhookEventType.DOCUMENT_REJECTED;
            case FAILED -> WebhookEventType.DOCUMENT_FAILED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        var evidence = i.evidence();
        String data = "{\"documentId\":\"" + i.id() + "\""
                + ",\"kind\":\"" + i.kind().name() + "\""
                + ",\"number\":" + q(i.number().map(n -> n.formatted()).orElse(null))
                + ",\"status\":\"" + i.status().name() + "\""
                + ",\"environment\":" + q(i.environment().map(Enum::name).orElse(null))
                + ",\"legallyValid\":" + (i.status() == InvoiceStatus.ACCEPTED && evidence.map(e -> e.environment().name().equals("PRODUCTION")).orElse(false))
                + ",\"documentKey\":" + q(evidence.map(e -> e.documentKey()).orElse(null))
                + ",\"issueDate\":\"" + i.issueDate() + "\""
                + ",\"total\":\"" + i.calculation().payableAmount().toPlainString() + "\""
                + ",\"currency\":\"" + i.currency() + "\""
                + ",\"customer\":{\"identificationType\":\"" + i.customer().identification().type().name()
                + "\",\"identificationNumber\":" + q(i.customer().identification().number()) + "}"
                + ",\"creditedDocumentId\":" + q(i.creditNote().map(cn -> cn.creditedInvoiceId().toString()).orElse(null))
                + ",\"rejectionReasons\":[" + String.join(",", i.rejectionReasons().stream().map(DocumentWebhooks::q).toList()) + "]"
                + "}";
        webhooks.publish(i.tenantId(), type, data);
    }

    private static String q(String value) {
        return value == null ? "null" : Json.quote(value);
    }
}
