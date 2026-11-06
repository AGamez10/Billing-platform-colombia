package co.billingplatform.payments.domain;

import co.billingplatform.shared.domain.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * Cuenta de pasarela del comercio (su propia cuenta, ADR-0020). Solo guarda referencias a las
 * llaves cifradas; los valores nunca viajan en este objeto ni en logs.
 */
public record PaymentAccount(UUID id, TenantId tenantId, Gateway gateway, Mode mode,
                             String identityKeyRef, String secretKeyRef) {

    public enum Gateway { BOLD }

    /** TEST: llaves de pruebas de la pasarela, sin dinero real. LIVE: cobros reales. */
    public enum Mode { TEST, LIVE }

    public PaymentAccount {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(gateway);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(identityKeyRef);
        Objects.requireNonNull(secretKeyRef);
    }
}
