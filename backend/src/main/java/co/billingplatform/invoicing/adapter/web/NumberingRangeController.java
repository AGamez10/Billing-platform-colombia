package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.application.NumberingRangeService;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/numbering-ranges")
@Tag(name = "Resoluciones de numeración")
@SecurityRequirement(name = "bearer")
class NumberingRangeController {

    /** @param kind INVOICE (por defecto) o POS_EQUIVALENT: cada tipo tiene su propia resolución. */
    record RangeRequest(
            DocumentKind kind,
            @NotNull ProviderEnvironment environment,
            @NotBlank @Size(max = 50) String resolutionNumber,
            @Size(max = 10) String prefix,
            @NotNull @Positive Long rangeFrom,
            @NotNull @Positive Long rangeTo,
            @NotNull LocalDate validFrom,
            @NotNull LocalDate validTo) {}

    record RangeView(UUID id, String kind, String environment, String resolutionNumber, String prefix, long rangeFrom,
                     long rangeTo, LocalDate validFrom, LocalDate validTo, long nextNumber, long remaining) {
        static RangeView of(NumberingRange r) {
            return new RangeView(r.id(), r.documentKind().name(), r.environment().name(), r.resolutionNumber(), r.prefix(), r.rangeFrom(),
                    r.rangeTo(), r.validFrom(), r.validTo(), r.nextNumber(), r.remaining());
        }
    }

    private final NumberingRangeService ranges;
    private final TenantTransactions tx;

    NumberingRangeController(NumberingRangeService ranges, TenantTransactions tx) {
        this.ranges = ranges;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una resolución de numeración (datos tal como aparecen en la resolución de la DIAN). "
            + "SIMULATION es solo para pruebas con el proveedor simulado.")
    RangeView register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RangeRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.NUMBERING_MANAGE);
        return tx.inTenant(u.tenantId(), () -> RangeView.of(ranges.register(u.tenantId(), u.actor(),
                new NumberingRangeService.NewRange(r.kind(), r.environment(), r.resolutionNumber(), r.prefix(), r.rangeFrom(),
                        r.rangeTo(), r.validFrom(), r.validTo()))));
    }

    @GetMapping
    @Operation(summary = "Resoluciones activas, con números disponibles")
    List<RangeView> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> ranges.list(u.tenantId()).stream().map(RangeView::of).toList());
    }
}
