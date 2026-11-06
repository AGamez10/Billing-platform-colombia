package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.adapter.persistence.JdbcInvoiceQueries;
import co.billingplatform.invoicing.adapter.persistence.JdbcInvoiceQueries.InvoiceSummary;
import co.billingplatform.invoicing.adapter.persistence.JdbcInvoiceRepository;
import co.billingplatform.invoicing.application.FinalizeInvoiceService;
import co.billingplatform.invoicing.application.InvoiceDraftService;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.tax.domain.Discount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Facturas")
@SecurityRequirement(name = "bearer")
class InvoiceController {

    enum DiscountKind { PERCENT, AMOUNT }

    record DiscountRequest(@NotNull DiscountKind kind, @NotNull @DecimalMin("0") BigDecimal value) {
        Discount toDomain() {
            if (kind == DiscountKind.PERCENT) {
                if (value.compareTo(new BigDecimal("100")) > 0) {
                    throw new DomainException("INVALID_DISCOUNT", "Discount percent must be between 0 and 100");
                }
                return new Discount.Percentage(value);
            }
            return new Discount.Amount(value);
        }
    }

    record LineRequest(
            @NotBlank @Size(max = 300) String description,
            @NotBlank @Size(max = 10) String unitCode,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitPrice,
            @Valid DiscountRequest discount,
            @Size(max = 5) List<@NotBlank @Size(max = 30) String> taxRateCodes) {
        InvoiceLine toDomain() {
            return new InvoiceLine(null, description, unitCode, quantity, unitPrice,
                    discount == null ? Discount.NONE : discount.toDomain(), taxRateCodes);
        }
    }

    record DraftRequest(@NotNull UUID customerId, @NotEmpty @Size(max = 500) List<@NotNull @Valid LineRequest> lines) {
        InvoiceDraftService.DraftCommand command() {
            return new InvoiceDraftService.DraftCommand(customerId, lines.stream().map(LineRequest::toDomain).toList());
        }
    }

    private final InvoiceDraftService drafts;
    private final FinalizeInvoiceService finalizer;
    private final JdbcInvoiceRepository invoices;
    private final JdbcInvoiceQueries queries;
    private final TenantTransactions tx;

    InvoiceController(InvoiceDraftService drafts, FinalizeInvoiceService finalizer, JdbcInvoiceRepository invoices,
                      JdbcInvoiceQueries queries, TenantTransactions tx) {
        this.drafts = drafts;
        this.finalizer = finalizer;
        this.invoices = invoices;
        this.queries = queries;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear borrador (totales e impuestos los calcula el servidor; no consume numeración)")
    InvoiceView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DraftRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(drafts.create(u.tenantId(), u.actor(), r.command())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Reemplazar el contenido de un borrador")
    InvoiceView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DraftRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(drafts.update(u.tenantId(), u.actor(), id, r.command())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Borrar un borrador (una factura emitida no se borra: se ajusta con nota crédito)")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        tx.inTenant(u.tenantId(), () -> {
            drafts.delete(u.tenantId(), u.actor(), id);
            return null;
        });
    }

    @PostMapping("/{id}/finalize")
    @Operation(summary = "Emitir: asigna número de la resolución vigente y programa el envío al proveedor. "
            + "Repetir la llamada no emite dos veces.")
    InvoiceView finalizeInvoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_FINALIZE);
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(finalizer.finalizeInvoice(u.tenantId(), u.actor(), id)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar factura: estado, número, totales, evidencia del proveedor e historial")
    InvoiceView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(invoices.findForRead(u.tenantId(), id)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"))));
    }

    @GetMapping
    @Operation(summary = "Listar facturas (más recientes primero, paginado, máx. 100)")
    Page<InvoiceSummary> list(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(required = false) InvoiceStatus status,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(required = false) Integer offset) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> queries.list(u.tenantId(), status, limit, offset));
    }
}
