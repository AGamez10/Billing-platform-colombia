package co.billingplatform.invoicing.application;

import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

/**
 * Crear borradores por API con {@code Idempotency-Key} (opcional): un integrador que reintenta
 * tras un corte de red recibe el mismo borrador en vez de uno duplicado; la misma clave con otro
 * contenido se rechaza. Comparte la tabla de claves con la venta POS, con espacio de nombres propio.
 */
public final class IdempotentDrafts {

    private final InvoiceDraftService drafts;
    private final InvoiceRepository invoices;
    private final PosSaleService.IdempotencyKeys keys;

    public IdempotentDrafts(InvoiceDraftService drafts, InvoiceRepository invoices, PosSaleService.IdempotencyKeys keys) {
        this.drafts = Objects.requireNonNull(drafts);
        this.invoices = Objects.requireNonNull(invoices);
        this.keys = Objects.requireNonNull(keys);
    }

    public Invoice create(TenantId tenantId, Actor actor, String idempotencyKey, InvoiceDraftService.DraftCommand command,
                          String canonicalRequest) {
        if (idempotencyKey == null) {
            return drafts.create(tenantId, actor, command);
        }
        if (!idempotencyKey.matches("[A-Za-z0-9_-]{8,120}")) {
            throw new DomainException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must have 8 to 120 letters, digits, '-' or '_'");
        }
        String key = "draft:" + idempotencyKey;
        byte[] hash = sha256(canonicalRequest);
        Optional<PosSaleService.IdempotencyKeys.Stored> previous = keys.reserve(tenantId, key, hash);
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(previous.get().requestHash(), hash)) {
                throw new DomainException("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request");
            }
            if (previous.get().resourceId() == null) {
                throw new DomainException("IDEMPOTENCY_KEY_IN_PROGRESS", "The request with this key is still being processed");
            }
            return invoices.findById(tenantId, previous.get().resourceId())
                    .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        }
        Invoice created = drafts.create(tenantId, actor, command);
        keys.complete(tenantId, key, created.id());
        return created;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
