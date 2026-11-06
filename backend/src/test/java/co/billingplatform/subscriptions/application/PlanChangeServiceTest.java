package co.billingplatform.subscriptions.application;

import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.subscriptions.domain.PlanChangeRequest;
import co.billingplatform.subscriptions.domain.PlanChangeRequest.Cycle;
import co.billingplatform.subscriptions.domain.Subscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlanChangeServiceTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID user = UUID.randomUUID();
    private Subscription current = subscription("TRIAL", Subscription.Status.TRIAL);
    private final List<PlanChangeRequest> stored = new ArrayList<>();
    private final List<AuditTrail.Event> audit = new ArrayList<>();

    private final PlanCatalog catalog = () -> List.of(
            new PlanCatalog.PublicPlan("TRIAL", "Prueba", "TRIAL", 20, "TOTAL", 1, null, false, null, null),
            new PlanCatalog.PublicPlan("MICRO", "Micro", "GENERAL", 15, "MONTHLY", 1, null, false, "69000", null),
            new PlanCatalog.PublicPlan("EMPRENDEDOR", "Emprendedor", "GENERAL", 40, "MONTHLY", 2, "12900", true, "129000", "150"),
            new PlanCatalog.PublicPlan("COMERCIO_1200", "Comercio 1.200", "COMMERCE", 1200, "MONTHLY", 5, "64900", true, "713900", "60"));

    private final PlanChangeService.Requests requests = new PlanChangeService.Requests() {
        @Override public void replacePending(PlanChangeRequest request) { stored.clear(); stored.add(request); }
        @Override public Optional<PlanChangeRequest> pending(TenantId t) { return stored.stream().findFirst(); }
    };

    private final PlanChangeService service = new PlanChangeService(t -> Optional.of(current), catalog, requests, audit::add,
            Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneOffset.UTC));

    private Subscription subscription(String plan, Subscription.Status status) {
        return new Subscription(UUID.randomUUID(), tenant, plan, status, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
                Subscription.QuotaScope.MONTHLY, null, null, null);
    }

    @Test
    @DisplayName("guarda el plan elegido con el precio cotizado del ciclo; una solicitud nueva reemplaza la anterior")
    void requestsWithQuotedPrice() {
        PlanChangeRequest monthly = service.request(tenant, Actor.user(user), user, "COMERCIO_1200", Cycle.MONTHLY);
        assertEquals("TRIAL", monthly.fromPlan());
        assertEquals(new BigDecimal("64900"), monthly.quotedPrice());
        PlanChangeRequest annual = service.request(tenant, Actor.user(user), user, "EMPRENDEDOR", Cycle.ANNUAL);
        assertEquals(new BigDecimal("129000"), annual.quotedPrice());
        assertEquals(Optional.of(annual), service.pending(tenant));
        assertEquals("PLAN_CHANGE_REQUESTED", audit.get(1).action());
    }

    @Test
    @DisplayName("rechaza ciclos que el plan no vende, la prueba gratis, planes inexistentes y el plan ya activo")
    void rejectsInvalidRequests() {
        assertEquals("PLAN_CYCLE_NOT_AVAILABLE", assertThrows(DomainException.class,
                () -> service.request(tenant, Actor.user(user), user, "MICRO", Cycle.MONTHLY)).code(), "Micro es solo anual");
        assertEquals("PLAN_NOT_AVAILABLE", assertThrows(DomainException.class,
                () -> service.request(tenant, Actor.user(user), user, "TRIAL", Cycle.ANNUAL)).code());
        assertEquals("PLAN_NOT_FOUND", assertThrows(DomainException.class,
                () -> service.request(tenant, Actor.user(user), user, "GRATIS", Cycle.ANNUAL)).code());
        current = subscription("EMPRENDEDOR", Subscription.Status.ACTIVE);
        assertEquals("PLAN_ALREADY_ACTIVE", assertThrows(DomainException.class,
                () -> service.request(tenant, Actor.user(user), user, "EMPRENDEDOR", Cycle.MONTHLY)).code());
        current = subscription("EMPRENDEDOR", Subscription.Status.PAST_DUE);
        assertDoesNotThrow(() -> service.request(tenant, Actor.user(user), user, "EMPRENDEDOR", Cycle.MONTHLY),
                "con pago vencido sí puede pedir el mismo plan para reactivarlo");
        assertEquals(1, stored.size());
    }
}
