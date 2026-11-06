package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.PlanChangeRequest;
import co.billingplatform.subscriptions.domain.Subscription;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * La oferta de mejora de plan (antes de los excedentes, ADR-0014): el usuario elige un plan y un
 * ciclo, y queda una solicitud con el precio cotizado. Una solicitud nueva reemplaza a la anterior.
 */
public final class PlanChangeService {

    /** Puerto de persistencia de las solicitudes. */
    public interface Requests {
        /** Cancela la solicitud pendiente anterior (si la hay) y guarda la nueva. */
        void replacePending(PlanChangeRequest request);

        Optional<PlanChangeRequest> pending(TenantId tenantId);
    }

    private final SubscriptionRepository subscriptions;
    private final PlanCatalog catalog;
    private final Requests requests;
    private final AuditTrail audit;
    private final Clock clock;

    public PlanChangeService(SubscriptionRepository subscriptions, PlanCatalog catalog, Requests requests,
                             AuditTrail audit, Clock clock) {
        this.subscriptions = Objects.requireNonNull(subscriptions);
        this.catalog = Objects.requireNonNull(catalog);
        this.requests = Objects.requireNonNull(requests);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public PlanChangeRequest request(TenantId tenantId, Actor actor, UUID userId, String planCode,
                                     PlanChangeRequest.Cycle cycle) {
        Objects.requireNonNull(cycle, "cycle is required");
        Subscription current = subscriptions.lockCurrent(tenantId)
                .orElseThrow(() -> new DomainException("SUBSCRIPTION_REQUIRED", "The organization has no subscription"));
        PlanCatalog.PublicPlan plan = catalog.publicPlans().stream()
                .filter(p -> p.code().equals(planCode))
                .findFirst()
                .orElseThrow(() -> new DomainException("PLAN_NOT_FOUND", "Plan not found"));
        if ("TRIAL".equals(plan.family())) {
            throw new DomainException("PLAN_NOT_AVAILABLE", "The free trial cannot be requested");
        }
        if (plan.code().equals(current.planCode()) && current.status() == Subscription.Status.ACTIVE) {
            throw new DomainException("PLAN_ALREADY_ACTIVE", "This plan is already active");
        }
        String price = switch (cycle) {
            case MONTHLY -> plan.monthlyBillingAllowed() ? plan.monthlyPrice() : null;
            case ANNUAL -> plan.annualPrice();
        };
        if (price == null) {
            throw new DomainException("PLAN_CYCLE_NOT_AVAILABLE", "The plan is not sold with that billing cycle");
        }
        PlanChangeRequest request = new PlanChangeRequest(UUID.randomUUID(), tenantId, current.planCode(), plan.code(),
                cycle, new BigDecimal(price), userId, clock.instant());
        requests.replacePending(request);
        audit.record(new AuditTrail.Event(tenantId, actor, "PLAN_CHANGE_REQUESTED", "plan_change_request",
                request.id().toString(), Map.of("from", request.fromPlan(), "to", request.toPlan(),
                        "cycle", cycle.name(), "quotedPrice", request.quotedPrice().toPlainString())));
        return request;
    }

    public Optional<PlanChangeRequest> pending(TenantId tenantId) {
        return requests.pending(tenantId);
    }
}
