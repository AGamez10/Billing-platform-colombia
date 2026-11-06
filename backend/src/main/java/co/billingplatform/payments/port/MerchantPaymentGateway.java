package co.billingplatform.payments.port;

import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.domain.PaymentAccount;

import java.math.BigDecimal;
import java.util.List;

/** Puerto de pasarela de cobro del comercio. Cada pasarela es un adaptador (Bold primero). */
public interface MerchantPaymentGateway {

    PaymentAccount.Gateway gateway();

    /** Comprueba que las llaves funcionan y devuelve los medios de pago que la cuenta tiene habilitados. */
    List<String> verifyCredentials(Credentials credentials);

    /** Crea el cobro (link de pago con QR) por el monto exacto, con nuestra referencia única. */
    CreatedLink createLink(Credentials credentials, String reference, BigDecimal amount, String description);

    /** Estado actual consultado a la pasarela: es la fuente de verdad, no el cuerpo del webhook. */
    LinkStatus status(Credentials credentials, String linkId);

    /** Verifica la firma de un webhook recibido. */
    boolean verifyWebhook(Credentials credentials, byte[] body, String signature);

    /** Llaves en claro, solo en memoria durante la llamada. */
    record Credentials(PaymentAccount.Mode mode, String identityKey, String secretKey) {
        @Override
        public String toString() {
            return "Credentials[mode=" + mode + ", keys=***]";
        }
    }

    record CreatedLink(String linkId, String checkoutUrl) {}

    record LinkStatus(Charge.Status status, String transactionId, String paymentMethod) {}

    /** La pasarela no respondió o respondió con error: el cobro sigue como estaba. */
    class GatewayException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean credentialsRejected;

        public GatewayException(String message, boolean credentialsRejected) {
            super(message);
            this.credentialsRejected = credentialsRejected;
        }

        public boolean credentialsRejected() {
            return credentialsRejected;
        }
    }
}
