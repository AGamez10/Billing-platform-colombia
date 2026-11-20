package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.electronicinvoicing.domain.Correction;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.adapter.persistence.JdbcInvoiceQueries;
import co.billingplatform.invoicing.application.CreditNoteService;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import java.util.List;
import java.util.UUID;

/** Notas crédito sobre una factura validada (ADR-0028). La nota queda en borrador y se emite con /finalize. */
@RestController
@RequestMapping("/api/v1/invoices/{id}/credit-notes")
@Tag(name = "Notas crédito")
@SecurityRequirement(name = "bearer")
class CreditNoteController {

    record ReturnLineRequest(@Min(1) int lineNumber,
                             @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity) {}

    record CreditNoteRequest(
            @NotNull Correction.Concept concept,
            @NotBlank @Size(max = 500) String description,
            @Size(max = 500) List<@Valid ReturnLineRequest> lines) {}

    private final CreditNoteService creditNotes;
    private final JdbcInvoiceQueries queries;
    private final TenantTransactions tx;

    CreditNoteController(CreditNoteService creditNotes, JdbcInvoiceQueries queries, TenantTransactions tx) {
        this.creditNotes = creditNotes;
        this.queries = queries;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear el borrador de una nota crédito: ANNULMENT (todo) o RETURN (líneas y cantidades)")
    InvoiceView create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CreditNoteRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        List<CreditNoteService.ReturnLine> lines = r.lines() == null ? List.of()
                : r.lines().stream().map(l -> new CreditNoteService.ReturnLine(l.lineNumber(), l.quantity())).toList();
        return tx.inTenant(u.tenantId(), () -> InvoiceView.of(
                creditNotes.create(u.tenantId(), u.actor(), id, r.concept(), r.description(), lines)));
    }

    @GetMapping
    @Operation(summary = "Notas crédito de una factura (incluye borradores y rechazadas)")
    List<JdbcInvoiceQueries.InvoiceSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> queries.creditNotesOf(u.tenantId(), id));
    }
}
