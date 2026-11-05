package co.billingplatform.subscriptions.domain;

import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aceptación expresa del cliente de emitir documentos adicionales con cobro, después de que
 * se le ofreció mejorar el plan (decisión del propietario, ADR-0014).
 *
 * <p>Vale para una suscripción y un ciclo de cuota: al cambiar de plan o empezar un ciclo
 * nuevo se vuelve a ofrecer la mejora antes de cobrar. El precio queda congelado al aceptar.
 */
public record OverageConsent(
        TenantId tenantId,
        UUID subscriptionId,
        QuotaWindow window,
        BigDecimal unitPrice,
        Actor acceptedBy,
        Instant acceptedAt) {

    public OverageConsent {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(subscriptionId);
        Objects.requireNonNull(window);
        Objects.requireNonNull(unitPrice);
        Objects.requireNonNull(acceptedBy);
        Objects.requireNonNull(acceptedAt);
    }
}
