package co.billingplatform.payments.application;

import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.payments.port.MerchantPaymentGateway;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Conecta la cuenta de pasarela del comercio. Antes de guardar nada comprueba que las llaves
 * funcionan; las guarda cifradas y devuelve UNA sola vez el token de la URL del webhook.
 */
public final class PaymentAccountService {

    public record Connected(PaymentAccount account, List<String> paymentMethods, String webhookToken) {}

    private final PaymentPorts.PaymentAccounts accounts;
    private final PaymentPorts.SecretVault vault;
    private final MerchantPaymentGateway gateway;
    private final AuditTrail audit;
    private final SecureRandom random = new SecureRandom();

    public PaymentAccountService(PaymentPorts.PaymentAccounts accounts, PaymentPorts.SecretVault vault,
                                 MerchantPaymentGateway gateway, AuditTrail audit) {
        this.accounts = Objects.requireNonNull(accounts);
        this.vault = Objects.requireNonNull(vault);
        this.gateway = Objects.requireNonNull(gateway);
        this.audit = Objects.requireNonNull(audit);
    }

    public Connected connect(TenantId tenantId, Actor actor, PaymentAccount.Mode mode, String identityKey, String secretKey) {
        Objects.requireNonNull(mode, "mode is required");
        String identity = clean(identityKey, "identity key");
        // La pasarela firma los webhooks de pruebas con llave vacía; en vivo la secreta es obligatoria.
        String secret = secretKey == null ? "" : secretKey.strip();
        if (mode == PaymentAccount.Mode.LIVE && secret.isEmpty()) {
            throw new DomainException("PAYMENT_CREDENTIALS_INVALID", "The secret key is required for live payments");
        }
        MerchantPaymentGateway.Credentials credentials = new MerchantPaymentGateway.Credentials(mode, identity, secret);
        List<String> methods;
        try {
            methods = gateway.verifyCredentials(credentials);
        } catch (MerchantPaymentGateway.GatewayException e) {
            throw e.credentialsRejected()
                    ? new DomainException("PAYMENT_CREDENTIALS_REJECTED", "The payment gateway rejected these keys")
                    : new DomainException("PAYMENT_GATEWAY_UNAVAILABLE", "The payment gateway is not responding");
        }
        UUID id = UUID.randomUUID();
        String identityRef = "pay:" + gateway.gateway().name().toLowerCase() + ":" + id + ":identity";
        String secretRef = "pay:" + gateway.gateway().name().toLowerCase() + ":" + id + ":secret";
        vault.store(tenantId, identityRef, identity);
        vault.store(tenantId, secretRef, secret);
        String token = newToken();
        PaymentAccount account = new PaymentAccount(id, tenantId, gateway.gateway(), mode, identityRef, secretRef);
        accounts.activate(account, sha256(token));
        audit.record(new AuditTrail.Event(tenantId, actor, "PAYMENT_ACCOUNT_CONNECTED", "payment_account", id.toString(),
                Map.of("gateway", gateway.gateway().name(), "mode", mode.name())));
        return new Connected(account, methods, token);
    }

    public Optional<PaymentAccount> active(TenantId tenantId) {
        return accounts.findActive(tenantId);
    }

    private static String clean(String value, String name) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > 300 || v.chars().anyMatch(Character::isWhitespace)) {
            throw new DomainException("PAYMENT_CREDENTIALS_INVALID", "Invalid " + name);
        }
        return v;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
