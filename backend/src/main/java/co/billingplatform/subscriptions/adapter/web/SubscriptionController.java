package co.billingplatform.subscriptions.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.subscriptions.application.OverageConsentService;
import co.billingplatform.subscriptions.domain.OverageConsent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/subscription")
@Tag(name = "Suscripción y consumo")
@SecurityRequirement(name = "bearer")
class SubscriptionController {

    record QuotaView(String planCode, Integer limit, long used, LocalDate windowStart, LocalDate windowEndExclusive,
                     boolean limitReached, boolean overageAvailable, BigDecimal overageUnitPrice,
                     boolean overageAccepted, String countsOnly) {}

    record ConsentView(BigDecimal unitPrice, LocalDate windowStart, LocalDate windowEndExclusive, Instant acceptedAt) {
        static ConsentView of(OverageConsent c) {
            return new ConsentView(c.unitPrice(), c.window().start(), c.window().endExclusive(), c.acceptedAt());
        }
    }

    private final OverageConsentService overage;
    private final TenantTransactions tx;

    SubscriptionController(OverageConsentService overage, TenantTransactions tx) {
        this.overage = overage;
        this.tx = tx;
    }

    @GetMapping("/quota")
    @Operation(summary = "Documentos usados en el ciclo actual. Solo cuentan los emitidos en PRODUCTION.")
    QuotaView quota(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USAGE_READ);
        return tx.inTenant(u.tenantId(), () -> {
            var s = overage.status(u.tenantId());
            return new QuotaView(s.planCode(), s.limit(), s.used(), s.window().start(), s.window().endExclusive(),
                    s.limitReached(), s.overageAvailable(), s.overageUnitPrice(), s.overageAccepted(), "PRODUCTION");
        });
    }

    @PostMapping("/overage-consent")
    @Operation(summary = "Aceptar expresamente documentos adicionales con cobro en el ciclo actual "
            + "(la alternativa es mejorar el plan). Solo dueño o administrador; idempotente.")
    ConsentView acceptOverage(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.BILLING_MANAGE);
        return tx.inTenant(u.tenantId(), () -> ConsentView.of(overage.accept(u.tenantId(), u.actor())));
    }
}
