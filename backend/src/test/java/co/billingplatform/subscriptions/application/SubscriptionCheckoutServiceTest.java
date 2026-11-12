package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway.Payment;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway.PaymentStatus;
import co.billingplatform.subscriptions.domain.PlanChangeRequest.Cycle;
import co.billingplatform.subscriptions.domain.Subscription;
import co.billingplatform.subscriptions.domain.SubscriptionCheckout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionCheckoutServiceTest {

    private Instant now = Instant.parse("2026-11-12T15:00:00Z"); // 10:00 en Bogotá
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID user = UUID.randomUUID();
    private Subscription current = sub("TRIAL", Subscription.Status.TRIAL, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 11, 1));
    private final Map<UUID, SubscriptionCheckout> stored = new HashMap<>();
    private final List<Payment> payments = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private boolean gatewayDown = false;

    private Subscription sub(String plan, Subscription.Status status, LocalDate start, LocalDate end) {
        return new Subscription(UUID.randomUUID(), tenant, plan, status, start, end, Subscription.QuotaScope.MONTHLY, null, null, null);
    }

    private final PlanCatalog catalog = () -> List.of(
            new PlanCatalog.PublicPlan("TRIAL", "Prueba", "TRIAL", 20, "TOTAL", 1, null, false, null, null),
            new PlanCatalog.PublicPlan("MICRO", "Micro", "GENERAL", 15, "MONTHLY", 1, null, false, "69000.00", null),
            new PlanCatalog.PublicPlan("EMPRENDEDOR", "Emprendedor", "GENERAL", 40, "MONTHLY", 2, "12900.00", true, "129000.00", "150"));

    private final PaymentGateway gateway = new PaymentGateway() {
        @Override public Preference createPreference(Request r) {
            if (gatewayDown) throw new GatewayException("down");
            return new Preference("pref-" + r.externalReference(), "https://mp.example/" + r.externalReference());
        }
        @Override public Optional<Payment> payment(String id) { return payments.stream().filter(p -> p.id().equals(id)).findFirst(); }
        @Override public List<Payment> paymentsFor(String ref) {
            if (gatewayDown) throw new GatewayException("down");
            return payments.stream().filter(p -> ref.equals(p.externalReference())).toList().reversed();
        }
        @Override public boolean verifyWebhook(String d, String r, String s) { return true; }
    };

    private final SubscriptionBillingPorts.Checkouts checkouts = new SubscriptionBillingPorts.Checkouts() {
        @Override public void insert(SubscriptionCheckout c) { stored.put(c.id(), c); }
        @Override public void update(SubscriptionCheckout c) { stored.put(c.id(), c); }
        @Override public Optional<SubscriptionCheckout> lock(TenantId t, UUID id) { return Optional.ofNullable(stored.get(id)); }
        @Override public Optional<TenantId> tenantOf(UUID id) { return stored.containsKey(id) ? Optional.of(tenant) : Optional.empty(); }
    };

    private final SubscriptionBillingPorts.Renewals renewals = new SubscriptionBillingPorts.Renewals() {
        @Override public void activate(TenantId t, UUID id, String plan, LocalDate start, LocalDate end) {
            current = sub(plan, Subscription.Status.ACTIVE, start, end);
        }
        @Override public int markOverdue(LocalDate today, int grace) { return 0; }
    };

    private SubscriptionCheckoutService service(boolean requireLive) {
        return new SubscriptionCheckoutService(t -> Optional.of(current), catalog, checkouts, renewals, gateway,
                new SubscriptionCheckoutService.Links(URI.create("https://app.co/api/v1/webhooks/mercadopago"), URI.create("https://app.co/plan")),
                requireLive, e -> audit.add(e.action()), clock);
    }

    private Payment pay(SubscriptionCheckout c, PaymentStatus status, String amount, boolean live) {
        Payment p = new Payment(String.valueOf(payments.size() + 1), status, c.id().toString(), new BigDecimal(amount), "COP", live);
        payments.add(p);
        return p;
    }

    @Test
    @DisplayName("salir de la prueba: el periodo empieza hoy (fecha de Colombia) y dura un mes")
    void fromTrial() {
        SubscriptionCheckoutService s = service(true);
        SubscriptionCheckout c = s.start(tenant, user, "EMPRENDEDOR", Cycle.MONTHLY);
        assertEquals(new BigDecimal("12900.00"), c.amount());
        assertTrue(c.checkoutUrl().orElseThrow().startsWith("https://"));
        pay(c, PaymentStatus.APPROVED, "12900", true);
        assertEquals(SubscriptionCheckout.Status.PAID, s.reconcile(tenant, c.id()).checkout().status());
        assertEquals("EMPRENDEDOR", current.planCode());
        assertEquals(Subscription.Status.ACTIVE, current.status());
        assertEquals(LocalDate.of(2026, 11, 12), current.periodStart());
        assertEquals(LocalDate.of(2026, 12, 12), current.periodEnd());
        assertTrue(audit.contains("SUBSCRIPTION_PAID"));
        s.reconcile(tenant, c.id());
        assertEquals(1, audit.stream().filter("SUBSCRIPTION_PAID"::equals).count(), "conciliar otra vez no activa dos veces");
    }

    @Test
    @DisplayName("renovar al día extiende desde el fin; pagar con la suscripción vencida empieza hoy; anual suma un año")
    void renewals() {
        SubscriptionCheckoutService s = service(true);
        current = sub("EMPRENDEDOR", Subscription.Status.ACTIVE, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 20));
        SubscriptionCheckout renew = s.start(tenant, user, "EMPRENDEDOR", Cycle.ANNUAL);
        pay(renew, PaymentStatus.APPROVED, "129000", true);
        s.reconcile(tenant, renew.id());
        assertEquals(LocalDate.of(2026, 10, 20), current.periodStart(), "el ancla del ciclo mensual no cambia");
        assertEquals(LocalDate.of(2027, 11, 20), current.periodEnd());

        current = sub("EMPRENDEDOR", Subscription.Status.PAST_DUE, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
        SubscriptionCheckout late = s.start(tenant, user, "EMPRENDEDOR", Cycle.MONTHLY);
        pay(late, PaymentStatus.APPROVED, "12900", true);
        s.reconcile(tenant, late.id());
        assertEquals(Subscription.Status.ACTIVE, current.status());
        assertEquals(LocalDate.of(2026, 11, 12), current.periodStart());
        assertEquals(LocalDate.of(2026, 12, 12), current.periodEnd());
    }

    @Test
    @DisplayName("solo un pago aprobado, por el valor exacto, en pesos y real activa el plan")
    void onlyValidPaymentsActivate() {
        SubscriptionCheckoutService s = service(true);
        SubscriptionCheckout c = s.start(tenant, user, "EMPRENDEDOR", Cycle.MONTHLY);
        pay(c, PaymentStatus.APPROVED, "1000", true);
        pay(c, PaymentStatus.APPROVED, "12900", false);
        pay(c, PaymentStatus.PENDING, "12900", true);
        SubscriptionCheckoutService.CheckoutState state = s.reconcile(tenant, c.id());
        assertEquals(SubscriptionCheckout.Status.PENDING, state.checkout().status());
        assertEquals(Optional.of(PaymentStatus.PENDING), state.lastAttempt());
        assertEquals("TRIAL", current.planCode());

        assertEquals(SubscriptionCheckout.Status.PAID, service(false).reconcile(tenant, c.id()).checkout().status(),
                "en pruebas (sin exigir pago real) el pago de prueba sí activa");
    }

    @Test
    @DisplayName("sin respuesta de la pasarela queda pendiente; a las 48 h vence; precios y ciclos del catálogo")
    void edgeCases() {
        SubscriptionCheckoutService s = service(true);
        assertEquals("PLAN_CYCLE_NOT_AVAILABLE", assertThrows(DomainException.class,
                () -> s.start(tenant, user, "MICRO", Cycle.MONTHLY)).code());
        assertEquals("PLAN_NOT_AVAILABLE", assertThrows(DomainException.class,
                () -> s.start(tenant, user, "TRIAL", Cycle.ANNUAL)).code());
        SubscriptionCheckout c = s.start(tenant, user, "MICRO", Cycle.ANNUAL);
        assertEquals(new BigDecimal("69000.00"), c.amount());
        gatewayDown = true;
        assertEquals(SubscriptionCheckout.Status.PENDING, s.reconcile(tenant, c.id()).checkout().status());
        assertEquals("SUBSCRIPTION_PAYMENTS_UNAVAILABLE", assertThrows(DomainException.class,
                () -> s.start(tenant, user, "MICRO", Cycle.ANNUAL)).code());
        gatewayDown = false;
        now = now.plus(Duration.ofHours(49));
        assertEquals(SubscriptionCheckout.Status.EXPIRED, s.reconcile(tenant, c.id()).checkout().status());
        assertEquals(Optional.empty(), s.checkoutOfPayment("no-existe"));
    }
}
