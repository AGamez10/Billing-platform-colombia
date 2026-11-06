package co.billingplatform.payments.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.adapter.web.LineRequest;
import co.billingplatform.invoicing.adapter.web.InvoiceView;
import co.billingplatform.payments.application.ChargeService;
import co.billingplatform.payments.domain.Charge;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pos-charges")
@Tag(name = "Cobros: cobra y la factura sale sola")
@SecurityRequirement(name = "bearer")
class ChargeController {

    record ChargeRequest(UUID customerId,
                         @NotEmpty @Size(max = 200) List<@NotNull @Valid LineRequest> lines) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChargeView(UUID id, String status, BigDecimal amount, String currency, String checkoutUrl, boolean test,
                      String paymentMethod, Instant paidAt, String issueBlockedCode, InvoiceView sale) {

        static ChargeView of(ChargeService.Started s, boolean test) {
            Charge c = s.charge();
            return new ChargeView(c.id(), c.status().name(), c.amount(), "COP",
                    c.status() == Charge.Status.PENDING ? c.checkoutUrl().orElse(null) : null, test,
                    c.paymentMethod().orElse(null), c.paidAt().orElse(null), s.issueBlockedCode(), InvoiceView.of(s.invoice()));
        }
    }

    private final ChargeService charges;
    private final TenantTransactions tx;

    ChargeController(ChargeService charges, TenantTransactions tx) {
        this.charges = charges;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cobrar una venta: crea el POS en borrador y un link de pago (con QR) por el total en la cuenta "
            + "del comercio. Cuando la pasarela confirma el pago, el POS se emite solo. Exige Idempotency-Key.")
    ChargeView start(@AuthenticationPrincipal Jwt jwt,
                     @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                     @Valid @RequestBody ChargeRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_FINALIZE);
        u.require(Permission.INVOICE_WRITE);
        // Antes de cobrar, en una transacción corta: ¿el documento podrá emitirse?
        tx.inTenant(u.tenantId(), () -> {
            charges.preflight(u.tenantId());
            return null;
        });
        return tx.inTenant(u.tenantId(), () -> view(u, charges.start(u.tenantId(), u.userId(), idempotencyKey,
                r.customerId(), r.lines().stream().map(LineRequest::toDomain).toList(), r.toString())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Estado del cobro. Consulta la pasarela si sigue pendiente; si quedó pagado, el POS ya salió "
            + "(o issueBlockedCode dice por qué no todavía).")
    ChargeView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_FINALIZE);
        return tx.inTenant(u.tenantId(), () -> view(u, charges.refresh(u.tenantId(), id)));
    }

    private ChargeView view(CurrentUser u, ChargeService.Started s) {
        return ChargeView.of(s, charges.isTestCharge(u.tenantId(), s.charge()));
    }
}
