package co.billingplatform.subscriptions.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.Subscription;
import co.billingplatform.subscriptions.domain.Subscription.Limits;
import co.billingplatform.subscriptions.domain.Subscription.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionUsageMeterTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private Subscription current;
    private long issuedInPeriod;
    private final List<String> recordedEnvironments = new ArrayList<>();

    private final SubscriptionUsageMeter meter = new SubscriptionUsageMeter(
            t -> Optional.ofNullable(current),
            new UsageRepository() {
                @Override public long countIssued(TenantId t, LocalDate from, LocalDate to) { return issuedInPeriod; }
                @Override public void recordIssued(TenantId t, UUID d, String p, String env, Instant at) { recordedEnvironments.add(env); }
            },
            Clock.systemUTC());

    private Subscription subscription(Status status, Integer planLimit, Integer override) {
        return new Subscription(tenant, "BASIC", status, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                new Limits(planLimit, 3), new Limits(override, null));
    }

    private ProviderAccount account(ProviderEnvironment env) {
        return new ProviderAccount(UUID.randomUUID(), tenant, new ProviderId("alpha"), env, null, null);
    }

    @Test
    @DisplayName("sin suscripción, cancelada o con pago vencido no se emite")
    void requiresUsableSubscription() {
        assertEquals("SUBSCRIPTION_REQUIRED", assertThrows(DomainException.class, () -> meter.ensureCanIssue(tenant)).code());
        current = subscription(Status.CANCELLED, null, null);
        assertEquals("SUBSCRIPTION_INACTIVE", assertThrows(DomainException.class, () -> meter.ensureCanIssue(tenant)).code());
        current = subscription(Status.PAST_DUE, null, null);
        assertEquals("SUBSCRIPTION_INACTIVE", assertThrows(DomainException.class, () -> meter.ensureCanIssue(tenant)).code(),
                "con pago vencido no se emiten documentos");
    }

    @Test
    @DisplayName("aplica el límite del plan y lo reemplaza con condiciones particulares")
    void enforcesLimitWithOverride() {
        current = subscription(Status.ACTIVE, 50, null);
        issuedInPeriod = 49;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant));
        issuedInPeriod = 50;
        assertEquals("DOCUMENT_QUOTA_EXCEEDED", assertThrows(DomainException.class, () -> meter.ensureCanIssue(tenant)).code());

        current = subscription(Status.ACTIVE, 50, 500);   // convenio particular
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant));
        assertEquals(3, current.effectiveLimits().maxUsers(), "lo no sobrescrito conserva el valor del plan");
    }

    @Test
    @DisplayName("un límite nulo significa sin límite")
    void nullLimitIsUnlimited() {
        current = subscription(Status.TRIAL, null, null);
        issuedInPeriod = 1_000_000;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant));
    }

    @Test
    @DisplayName("los documentos simulados no se registran como consumo")
    void simulationIsNotMetered() {
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.SIMULATION));
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.HABILITATION));
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.PRODUCTION));
        assertEquals(List.of("HABILITATION", "PRODUCTION"), recordedEnvironments);
    }
}
