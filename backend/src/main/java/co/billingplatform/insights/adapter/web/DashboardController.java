package co.billingplatform.insights.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.insights.application.InsightsService;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Inicio")
@SecurityRequirement(name = "bearer")
class DashboardController {

    private final InsightsService insights;
    private final TenantTransactions tx;

    DashboardController(InsightsService insights, TenantTransactions tx) {
        this.insights = insights;
        this.tx = tx;
    }

    @GetMapping("/api/v1/dashboard")
    @Operation(summary = "Ventas del mes en el ambiente activo (aceptadas menos notas crédito), por día, y pendientes "
            + "ordenados por gravedad. Los del plan solo para quien puede verlo.")
    InsightsService.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> insights.dashboard(u.tenantId(), u.role().can(Permission.USAGE_READ)));
    }
}
