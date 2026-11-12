package co.billingplatform.subscriptions.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.subscriptions.application.SubscriptionCheckoutService;
import co.billingplatform.subscriptions.application.SubscriptionRepository;
import co.billingplatform.subscriptions.domain.PlanChangeRequest;
import co.billingplatform.subscriptions.domain.SubscriptionCheckout;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Pago de los planes (ADR-0024). Sin Mercado Pago configurado, el pago en línea aparece deshabilitado. */
@RestController
@RequestMapping("/api/v1/subscription")
@Tag(name = "Suscripción y consumo")
@SecurityRequirement(name = "bearer")
class SubscriptionPaymentsController {

    record CheckoutBody(@NotBlank @Size(max = 30) String planCode, @NotNull PlanChangeRequest.Cycle cycle) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CheckoutView(UUID id, String status, String planCode, String cycle, BigDecimal amount, String checkoutUrl,
                        String lastAttempt, Instant paidAt) {
        static CheckoutView of(SubscriptionCheckout c, String lastAttempt) {
            return new CheckoutView(c.id(), c.status().name(), c.planCode(), c.cycle().name(), c.amount(),
                    c.status() == SubscriptionCheckout.Status.PENDING ? c.checkoutUrl().orElse(null) : null, lastAttempt,
                    c.paidAt().orElse(null));
        }
    }

    record SubscriptionView(String planCode, String status, LocalDate periodStart, LocalDate periodEnd, boolean onlinePayments) {}

    private final ObjectProvider<SubscriptionCheckoutService> checkouts;
    private final SubscriptionRepository subscriptions;
    private final TenantTransactions tx;

    SubscriptionPaymentsController(ObjectProvider<SubscriptionCheckoutService> checkouts, SubscriptionRepository subscriptions,
                                   TenantTransactions tx) {
        this.checkouts = checkouts;
        this.subscriptions = subscriptions;
        this.tx = tx;
    }

    @GetMapping
    @Operation(summary = "Plan, estado y vigencia de la suscripción, y si se puede pagar en línea.")
    SubscriptionView current(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USAGE_READ);
        return tx.inTenant(u.tenantId(), () -> subscriptions.lockCurrent(u.tenantId())
                .map(s -> new SubscriptionView(s.planCode(), s.status().name(), s.periodStart(), s.periodEnd(),
                        checkouts.getIfAvailable() != null))
                .orElseThrow(() -> new co.billingplatform.shared.domain.DomainException("SUBSCRIPTION_REQUIRED",
                        "The organization has no subscription")));
    }

    @PostMapping("/checkouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pagar un plan (mensual o anual) con Mercado Pago. Devuelve checkoutUrl; el plan se activa cuando "
            + "Mercado Pago confirma el pago. Solo dueño o administrador.")
    CheckoutView start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckoutBody body) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.BILLING_MANAGE);
        SubscriptionCheckoutService service = service();
        return tx.inTenant(u.tenantId(), () -> CheckoutView.of(service.start(u.tenantId(), u.userId(), body.planCode(), body.cycle()), null));
    }

    @GetMapping("/checkouts/{id}")
    @Operation(summary = "Estado del pago. Consulta a Mercado Pago si sigue pendiente; si quedó aprobado, el plan ya está activo.")
    CheckoutView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USAGE_READ);
        SubscriptionCheckoutService service = service();
        return tx.inTenant(u.tenantId(), () -> {
            SubscriptionCheckoutService.CheckoutState state = service.reconcile(u.tenantId(), id);
            return CheckoutView.of(state.checkout(), state.lastAttempt().map(Enum::name).orElse(null));
        });
    }

    private SubscriptionCheckoutService service() {
        SubscriptionCheckoutService service = checkouts.getIfAvailable();
        if (service == null) {
            throw new co.billingplatform.shared.domain.DomainException("SUBSCRIPTION_PAYMENTS_NOT_CONFIGURED",
                    "Online payment of plans is not configured");
        }
        return service;
    }
}
