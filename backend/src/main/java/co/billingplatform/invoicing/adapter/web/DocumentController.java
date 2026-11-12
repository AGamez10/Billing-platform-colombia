package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.invoicing.adapter.persistence.JdbcInvoiceRepository;
import co.billingplatform.invoicing.application.DocumentSharingService;
import co.billingplatform.invoicing.application.IssuerDirectory;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.shared.application.Municipalities;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Representación gráfica y envío al comprador de un documento validado (ADR-0027). */
@RestController
@Tag(name = "Documentos")
class DocumentController {

    record SendRequest(@Size(max = 254) String email) {}

    record ViewRequest(@NotBlank @Size(max = 100) String token) {}

    record LinkView(String link, Instant expiresAt) {}

    record SentView(String sentTo, Instant expiresAt) {}

    private final DocumentSharingService sharing;
    private final JdbcInvoiceRepository invoices;
    private final IssuerDirectory issuers;
    private final Municipalities municipalities;
    private final TenantTransactions tx;

    DocumentController(DocumentSharingService sharing, JdbcInvoiceRepository invoices, IssuerDirectory issuers,
                       Municipalities municipalities, TenantTransactions tx) {
        this.sharing = sharing;
        this.invoices = invoices;
        this.issuers = issuers;
        this.municipalities = municipalities;
        this.tx = tx;
    }

    @GetMapping("/api/v1/invoices/{id}/sheet")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Datos de la representación gráfica (emisor, comprador, resolución, totales, CUFE y QR). "
            + "Solo documentos validados.")
    DocumentSheet sheet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> load(u.tenantId(), id));
    }

    @PostMapping("/api/v1/invoices/{id}/share-links")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Enlace de solo lectura para el comprador (WhatsApp, copiar). Vence en 180 días; "
            + "el token se entrega una sola vez.")
    LinkView link(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        var shared = tx.inTenant(u.tenantId(), () -> sharing.createLink(u.tenantId(), u.userId(), id));
        return new LinkView(shared.link(), shared.expiresAt());
    }

    @PostMapping("/api/v1/invoices/{id}/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Enviar por correo al comprador (o a otro correo) con el enlace al documento")
    SentView send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) SendRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_WRITE);
        var shared = tx.inTenant(u.tenantId(), () -> sharing.sendByEmail(u.tenantId(), u.userId(), id, r == null ? null : r.email()));
        return new SentView(shared.sentTo(), shared.expiresAt());
    }

    /** Público: el comprador abre el enlace. POST para que el token no quede en registros de acceso. */
    @PostMapping("/api/v1/public/documents/view")
    @Operation(summary = "Documento compartido (sin cuenta), por el token del enlace")
    ResponseEntity<DocumentSheet> view(@Valid @RequestBody ViewRequest r) {
        DocumentSharingService.Target target = tx.withoutTenant(() -> sharing.resolve(r.token()))
                .orElseThrow(() -> new DomainException("SHARE_LINK_NOT_FOUND", "The link does not exist or expired"));
        DocumentSheet sheet = tx.inTenant(target.tenantId(), () -> load(target.tenantId(), target.invoiceId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sheet);
    }

    private DocumentSheet load(TenantId tenantId, UUID invoiceId) {
        var invoice = invoices.findForRead(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        if (invoice.evidence().isEmpty()) {
            throw new DomainException("INVOICE_NOT_VALIDATED", "Only validated documents have a graphical representation");
        }
        return DocumentSheet.of(invoice, issuers.issuerOf(tenantId), municipalities);
    }
}
