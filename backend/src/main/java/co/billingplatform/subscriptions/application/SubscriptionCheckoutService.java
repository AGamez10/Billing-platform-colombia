package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway.Payment;
import co.billingplatform.subscriptions.application.SubscriptionBillingPorts.PaymentGateway.PaymentStatus;
import co.billingplatform.subscriptions.domain.PlanChangeRequest.Cycle;
import co.billingplatform.subscriptions.domain.Subscription;
import co.billingplatform.subscriptions.domain.SubscriptionCheckout;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pago de los planes con Mercado Pago (ADR-0024).
 *
 * <ol>
 *   <li>{@link #start}: se fija plan, ciclo y valor en un checkout y se crea la preferencia de
 *       pago; el cliente paga en Mercado Pago (tarjeta, PSE y los medios que ofrezca).</li>
 *   <li>{@link #reconcile}: se le pregunta a Mercado Pago, la fuente de verdad, por los pagos de
 *       ese checkout. Un pago aprobado por exactamente ese valor activa el plan, una sola vez.
 *       Lo invocan el regreso desde Mercado Pago, el aviso firmado y la conciliación periódica.</li>
 * </ol>
 *
 * <p>Vigencia: renovar el mismo plan al día extiende el periodo desde su fin (no se pierden días);
 * cambiar de plan, salir de la prueba o pagar con la suscripción vencida empieza un periodo nuevo
 * hoy. Sin prorrateo en el piloto.
 */
public final class SubscriptionCheckoutService {

    public static final Duration CHECKOUT_VALIDITY = Duration.ofHours(48);

    public record Links(URI notificationUrl, URI returnUrl) {}

    /** @param lastAttempt estado del último intento en la pasarela, para orientar al usuario */
    public record CheckoutState(SubscriptionCheckout checkout, Optional<PaymentStatus> lastAttempt) {}

    private final SubscriptionRepository subscriptions;
    private final PlanCatalog catalog;
    private final SubscriptionBillingPorts.Checkouts checkouts;
    private final SubscriptionBillingPorts.Renewals renewals;
    private final PaymentGateway gateway;
    private final Links links;
    private final boolean requireLiveMode;
    private final AuditTrail audit;
    private final Clock clock;

    public SubscriptionCheckoutService(SubscriptionRepository subscriptions, PlanCatalog catalog,
                                       SubscriptionBillingPorts.Checkouts checkouts, SubscriptionBillingPorts.Renewals renewals,
                                       PaymentGateway gateway, Links links, boolean requireLiveMode, AuditTrail audit,
                                       Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.catalog = Objects.requireNonNull(catalog);
        this.checkouts = Objects.requireNonNull(checkouts);
        this.renewals = Objects.requireNonNull(renewals);
        this.gateway = Objects.requireNonNull(gateway);
        this.links = Objects.requireNonNull(links);
        this.requireLiveMode = requireLiveMode;
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public SubscriptionCheckout start(TenantId tenantId, UUID userId, String planCode, Cycle cycle) {
        Objects.requireNonNull(cycle, "cycle is required");
        subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
        PlanCatalog.PublicPlan plan = catalog.publicPlans().stream()
                .filter(p -> p.code().equals(planCode))
                .findFirst()
                .orElseThrow(() -> new DomainException("PLAN_NOT_FOUND", "Plan not found"));
        if ("TRIAL".equals(plan.family())) {
            throw new DomainException("PLAN_NOT_AVAILABLE", "The free trial is not sold");
        }
        String price = switch (cycle) {
            case MONTHLY -> plan.monthlyBillingAllowed() ? plan.monthlyPrice() : null;
            case ANNUAL -> plan.annualPrice();
        };
        if (price == null || new BigDecimal(price).signum() <= 0) {
            throw new DomainException("PLAN_CYCLE_NOT_AVAILABLE", "The plan is not sold with that billing cycle");
        }
        Instant now = clock.instant();
        SubscriptionCheckout checkout = SubscriptionCheckout.open(tenantId, plan.code(), cycle, new BigDecimal(price), userId, now);
        URI back = URI.create(links.returnUrl() + "?pago=" + checkout.id());
        PaymentGateway.Preference preference;
        try {
            preference = gateway.createPreference(new PaymentGateway.Request(checkout.id().toString(),
                    "Plan " + plan.name() + (cycle == Cycle.ANNUAL ? " · pago anual" : " · pago mensual"),
                    checkout.amount(), links.notificationUrl(), back, back, back, now.plus(CHECKOUT_VALIDITY)));
        } catch (PaymentGateway.GatewayException e) {
            throw new DomainException("SUBSCRIPTION_PAYMENTS_UNAVAILABLE", "The payment gateway is not responding");
        }
        checkout.preferenceCreated(preference.id(), preference.checkoutUrl());
        checkouts.insert(checkout);
        audit.record(new AuditTrail.Event(tenantId, Actor.user(userId), "SUBSCRIPTION_CHECKOUT_STARTED", "subscription_checkout",
                checkout.id().toString(), Map.of("plan", plan.code(), "cycle", cycle.name(),
                        "amount", checkout.amount().toPlainString())));
        return checkout;
    }

    /** Dentro del tenant del checkout: consulta la pasarela y, si hay un pago aprobado válido, activa el plan. */
    public CheckoutState reconcile(TenantId tenantId, UUID checkoutId) {
        SubscriptionCheckout checkout = checkouts.lock(tenantId, checkoutId)
                .orElseThrow(() -> new DomainException("CHECKOUT_NOT_FOUND", "Checkout not found"));
        if (checkout.status() != SubscriptionCheckout.Status.PENDING) {
            return new CheckoutState(checkout, Optional.empty());
        }
        List<Payment> attempts;
        try {
            attempts = gateway.paymentsFor(checkout.id().toString());
        } catch (PaymentGateway.GatewayException e) {
            return new CheckoutState(checkout, Optional.empty()); // se reintenta en la próxima consulta
        }
        for (Payment payment : attempts) {
            if (valid(checkout, payment)) {
                apply(checkout, payment);
                return new CheckoutState(checkout, Optional.of(PaymentStatus.APPROVED));
            }
        }
        if (clock.instant().isAfter(checkout.createdAt().plus(CHECKOUT_VALIDITY))) {
            checkout.expire();
            checkouts.update(checkout);
        }
        return new CheckoutState(checkout, attempts.stream().findFirst().map(Payment::status));
    }

    /** Sin tenant: a qué checkout corresponde un pago avisado por la pasarela. */
    public Optional<CheckoutRef> checkoutOfPayment(String paymentId) {
        Optional<Payment> payment;
        try {
            payment = gateway.payment(paymentId);
        } catch (PaymentGateway.GatewayException e) {
            throw new DomainException("SUBSCRIPTION_PAYMENTS_UNAVAILABLE", "The payment gateway is not responding");
        }
        return payment.map(Payment::externalReference)
                .flatMap(SubscriptionCheckoutService::uuid)
                .flatMap(id -> checkouts.tenantOf(id).map(t -> new CheckoutRef(t, id)));
    }

    public record CheckoutRef(TenantId tenantId, UUID checkoutId) {}

    public boolean verifyWebhook(String dataId, String requestId, String signature) {
        return gateway.verifyWebhook(dataId, requestId, signature);
    }

    /** Sin tenant: suscripciones pagadas cuyo periodo terminó hace más de {@code graceDays}. */
    public int markOverdue(int graceDays) {
        return renewals.markOverdue(ColombianTime.today(clock), graceDays);
    }

    private boolean valid(SubscriptionCheckout checkout, Payment payment) {
        return payment.status() == PaymentStatus.APPROVED
                && checkout.id().toString().equals(payment.externalReference())
                && "COP".equals(payment.currency())
                && payment.amount() != null && payment.amount().compareTo(checkout.amount()) == 0
                && (payment.liveMode() || !requireLiveMode); // un pago de prueba nunca activa un plan real
    }

    private void apply(SubscriptionCheckout checkout, Payment payment) {
        TenantId tenantId = checkout.tenantId();
        if (!checkout.markPaid(payment.id(), clock.instant())) {
            return;
        }
        checkouts.update(checkout);
        Subscription current = subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
        LocalDate today = ColombianTime.today(clock);
        boolean renewal = current.status() == Subscription.Status.ACTIVE && current.planCode().equals(checkout.planCode())
                && current.periodEnd().isAfter(today);
        LocalDate start = renewal ? current.periodStart() : today;
        LocalDate from = renewal ? current.periodEnd() : today;
        LocalDate end = checkout.cycle() == Cycle.ANNUAL ? from.plusYears(1) : from.plusMonths(1);
        renewals.activate(tenantId, current.id(), checkout.planCode(), start, end);
        audit.record(new AuditTrail.Event(tenantId, Actor.user(checkout.createdBy()), "SUBSCRIPTION_PAID", "subscription_checkout",
                checkout.id().toString(), Map.of("plan", checkout.planCode(), "cycle", checkout.cycle().name(),
                        "amount", checkout.amount().toPlainString(), "paymentId", payment.id(),
                        "periodEnd", end.toString(), "renewal", String.valueOf(renewal))));
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
