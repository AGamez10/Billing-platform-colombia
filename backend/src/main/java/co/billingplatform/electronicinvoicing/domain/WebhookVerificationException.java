package co.billingplatform.electronicinvoicing.domain;

/** El webhook no pudo autenticarse. La capa web responde 401 sin dar detalles. */
public class WebhookVerificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WebhookVerificationException(String message) {
        super(message);
    }
}
