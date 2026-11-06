package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.application.PosSaleService;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pos-sales")
@Tag(name = "Venta rápida (POS)")
@SecurityRequirement(name = "bearer")
class PosSaleController {

    /** @param customerId opcional: sin él, la venta es a consumidor final. */
    record SaleRequest(UUID customerId,
                       @NotEmpty @Size(max = 200) List<@NotNull @Valid LineRequest> lines) {}

    private final PosSaleService sales;
    private final TenantTransactions tx;

    PosSaleController(PosSaleService sales, TenantTransactions tx) {
        this.sales = sales;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Vender: crea y emite un documento equivalente POS en un paso. Exige la cabecera "
            + "Idempotency-Key (una por venta): reintentar con la misma clave devuelve la misma venta.")
    InvoiceView sell(@AuthenticationPrincipal Jwt jwt,
                     @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                     @Valid @RequestBody SaleRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_FINALIZE);
        u.require(Permission.INVOICE_WRITE);
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(sales.sell(u.tenantId(), u.actor(), idempotencyKey,
                new PosSaleService.SaleCommand(r.customerId(), r.lines().stream().map(LineRequest::toDomain).toList()),
                r.toString())));
    }
}
