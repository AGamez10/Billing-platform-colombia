package co.billingplatform.payments.port;

import co.billingplatform.payments.domain.Charge;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.shared.domain.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puertos de persistencia del módulo de cobros. */
public final class PaymentPorts {

    private PaymentPorts() {}

    public interface PaymentAccounts {
        /** Guarda la cuenta como la activa del tenant (desactiva la anterior). */
        void activate(PaymentAccount account, byte[] webhookTokenHash);

        Optional<PaymentAccount> findActive(TenantId tenantId);

        Optional<PaymentAccount> findById(TenantId tenantId, UUID id);

        /** Sin tenant en contexto: ubica la cuenta por el token de la URL del webhook. */
        Optional<PaymentAccount> findByWebhookToken(String token);
    }

    public interface Charges {
        void insert(Charge charge);

        void update(Charge charge);

        /** Con bloqueo de fila: dos avisos simultáneos no emiten dos veces. */
        Optional<Charge> lock(TenantId tenantId, UUID chargeId);

        List<UUID> pendingSince(TenantId tenantId, Instant since);
    }

    /** Secretos cifrados por tenant (AES-GCM). */
    public interface SecretVault {
        void store(TenantId tenantId, String ref, String value);

        String read(String ref);
    }
}
