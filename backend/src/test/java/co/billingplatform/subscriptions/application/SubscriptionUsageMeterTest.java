package co.billingplatform.subscriptions.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.OverageConsent;
import co.billingplatform.subscriptions.domain.Subscription;
import co.billingplatform.subscriptions.domain.Subscription.Limits;
import co.billingplatform.subscriptions.domain.Subscription.QuotaScope;
import co.billingplatform.subscriptions.domain.Subscription.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionUsageMeterTest {

    private static final BigDecimal OVERAGE = new BigDecimal("150");
    /** 2026-11-20 en Bogotá. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZoneOffset.UTC);

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Actor actor = Actor.user(UUID.randomUUID());
    private Subscription current;
    private long issuedInWindow;
    private LocalDate countedFrom;
    private final List<String> recorded = new ArrayList<>();
    private final Map<String, OverageConsent> consentStore = new HashMap<>();
    private final List<String> audit = new ArrayList<>();

    private final UsageRepository usage = new UsageRepository() {
        @Override public long countIssued(TenantId t, LocalDate from, LocalDate to) { countedFrom = from; return issuedInWindow; }
        @Override public void recordIssued(TenantId t, UUID d, String p, String env, Instant at, BigDecimal overage) {
            recorded.add(env + (overage == null ? "" : ":overage=" + overage.toPlainString()));
        }
    };
    private final OverageConsentRepository consents = new OverageConsentRepository() {
        @Override public Optional<OverageConsent> find(TenantId t, UUID sub, LocalDate start) {
            return Optional.ofNullable(consentStore.get(sub + ":" + start));
        }
        @Override public void save(OverageConsent c) { consentStore.put(c.subscriptionId() + ":" + c.window().start(), c); }
    };

    private final SubscriptionUsageMeter meter = new SubscriptionUsageMeter(t -> Optional.ofNullable(current), usage, consents, CLOCK);
    private final OverageConsentService overage = new OverageConsentService(t -> Optional.ofNullable(current), usage, consents,
            e -> audit.add(e.action()), CLOCK);

    /** Plan pagado (mensual o anual) desde el 2026-01-15, con cuota mensual. */
    private Subscription paidPlan(Status status, Integer documents, Integer override, BigDecimal overagePrice) {
        return new Subscription(UUID.randomUUID(), tenant, "MICRO", status, LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15),
                QuotaScope.MONTHLY, new Limits(documents, null), new Limits(override, null), overagePrice);
    }

    private Subscription trial() {
        return new Subscription(UUID.randomUUID(), tenant, "TRIAL", Status.TRIAL, LocalDate.of(2026, 11, 1), LocalDate.of(2126, 11, 1),
                QuotaScope.TOTAL, new Limits(20, null), null, null);
    }

    private ProviderAccount account(ProviderEnvironment env) {
        return new ProviderAccount(UUID.randomUUID(), tenant, new ProviderId("alpha"), env, null, null);
    }

    private String issueCode() {
        return assertThrows(DomainException.class, () -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION)).code();
    }

    @Test
    @DisplayName("sin suscripción, cancelada o con pago vencido no se emite")
    void requiresUsableSubscription() {
        assertEquals("SUBSCRIPTION_REQUIRED", issueCode());
        current = paidPlan(Status.CANCELLED, null, null, OVERAGE);
        assertEquals("SUBSCRIPTION_INACTIVE", issueCode());
        current = paidPlan(Status.PAST_DUE, null, null, OVERAGE);
        assertEquals("SUBSCRIPTION_INACTIVE", issueCode(), "con pago vencido no se emiten documentos");
    }

    @Test
    @DisplayName("la cuota es mensual aunque el pago sea anual: cuenta desde el ciclo vigente")
    void monthlyQuotaWithAnnualPayment() {
        current = paidPlan(Status.ACTIVE, 15, null, OVERAGE);
        issuedInWindow = 3;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION));
        assertEquals(LocalDate.of(2026, 11, 15), countedFrom, "ciclo del 15-nov al 15-dic, no todo el año");
    }

    @Test
    @DisplayName("al llegar al límite no se cobra nada: se ofrece mejorar el plan o aceptar el excedente")
    void limitReachedRequiresExplicitChoice() {
        current = paidPlan(Status.ACTIVE, 15, null, OVERAGE);
        issuedInWindow = 15;
        assertEquals("DOCUMENT_QUOTA_REACHED", issueCode());
        assertTrue(recorded.isEmpty());

        var status = overage.status(tenant);
        assertTrue(status.limitReached());
        assertTrue(status.overageAvailable());
        assertFalse(status.overageAccepted());
        assertEquals(OVERAGE, status.overageUnitPrice());
    }

    @Test
    @DisplayName("tras aceptar expresamente, emite y registra cada documento adicional a $150")
    void acceptedOverageIsBilled() {
        current = paidPlan(Status.ACTIVE, 15, null, OVERAGE);
        issuedInWindow = 14;
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.PRODUCTION));

        issuedInWindow = 15;
        overage.accept(tenant, actor);
        overage.accept(tenant, actor);
        assertEquals(1, consentStore.size(), "aceptar dos veces no duplica");
        assertEquals(List.of("OVERAGE_ACCEPTED"), audit, "la aceptación queda auditada");

        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION));
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.PRODUCTION));
        assertEquals(List.of("PRODUCTION", "PRODUCTION:overage=150"), recorded);
    }

    @Test
    @DisplayName("al cambiar de plan se vuelve a ofrecer la mejora antes de cobrar excedentes")
    void consentDoesNotSurvivePlanChange() {
        current = paidPlan(Status.ACTIVE, 15, null, OVERAGE);
        issuedInWindow = 15;
        overage.accept(tenant, actor);

        current = paidPlan(Status.ACTIVE, 40, null, OVERAGE);   // nueva suscripción (mejoró a Emprendedor)
        issuedInWindow = 40;
        assertEquals("DOCUMENT_QUOTA_REACHED", issueCode());
    }

    @Test
    @DisplayName("la prueba gratis (20 documentos en total) no admite excedentes")
    void trialHasNoOverage() {
        current = trial();
        issuedInWindow = 19;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION));
        assertEquals(LocalDate.of(2026, 11, 1), countedFrom, "cuenta desde el inicio de la prueba, sin ciclos");

        issuedInWindow = 20;
        assertEquals("TRIAL_QUOTA_EXHAUSTED", issueCode());
        assertEquals("OVERAGE_NOT_AVAILABLE", assertThrows(DomainException.class, () -> overage.accept(tenant, actor)).code());
    }

    @Test
    @DisplayName("habilitación no consume cuota; los simulados ni se registran")
    void onlyProductionConsumesQuota() {
        current = trial();
        issuedInWindow = 20;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.HABILITATION));
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.SIMULATION));

        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.SIMULATION));
        meter.recordIssued(tenant, UUID.randomUUID(), account(ProviderEnvironment.HABILITATION));
        assertEquals(List.of("HABILITATION"), recorded);
    }

    @Test
    @DisplayName("condiciones particulares (convenios) reemplazan el límite del plan; null es sin límite")
    void overridesAndUnlimited() {
        current = paidPlan(Status.ACTIVE, 15, 500, OVERAGE);
        issuedInWindow = 100;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION));

        current = paidPlan(Status.ACTIVE, null, null, null);
        issuedInWindow = 1_000_000;
        assertDoesNotThrow(() -> meter.ensureCanIssue(tenant, ProviderEnvironment.PRODUCTION));
    }
}
