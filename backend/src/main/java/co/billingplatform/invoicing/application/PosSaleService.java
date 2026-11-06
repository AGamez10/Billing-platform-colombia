package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Venta rápida de comercio: crea y emite un documento equivalente POS en una sola operación.
 *
 * <p>Exige una clave de idempotencia por venta. Si la red falla y la caja reintenta, la misma
 * clave devuelve la misma venta, nunca una segunda. La misma clave con otro contenido se
 * rechaza: es un error de la caja, no una venta nueva.
 */
public final class PosSaleService {

    /** @param customerId {@code null} = consumidor final */
    public record SaleCommand(UUID customerId, List<InvoiceLine> lines) {}

    /** Claves de idempotencia de la API, por tenant (tabla {@code api_idempotency_keys}). */
    public interface IdempotencyKeys {
        /**
         * Reserva la clave para esta petición. Si otra transacción la está usando, espera a que
         * termine (bloqueo de fila).
         *
         * @return vacío si la clave es nueva y quedó reservada; si ya existía, lo guardado.
         */
        Optional<Stored> reserve(TenantId tenantId, String key, byte[] requestHash);

        void complete(TenantId tenantId, String key, UUID resourceId);

        record Stored(byte[] requestHash, UUID resourceId) {}
    }

    private final InvoiceDraftService drafts;
    private final FinalizeInvoiceService finalizer;
    private final InvoiceRepository invoices;
    private final IdempotencyKeys keys;

    public PosSaleService(InvoiceDraftService drafts, FinalizeInvoiceService finalizer, InvoiceRepository invoices,
                          IdempotencyKeys keys) {
        this.drafts = Objects.requireNonNull(drafts);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.invoices = Objects.requireNonNull(invoices);
        this.keys = Objects.requireNonNull(keys);
    }

    public Invoice sell(TenantId tenantId, Actor actor, String idempotencyKey, SaleCommand command, String canonicalRequest) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new DomainException("IDEMPOTENCY_KEY_REQUIRED",
                    "Header Idempotency-Key is required (8 to 128 letters, digits, '-' or '_'), one per sale");
        }
        byte[] hash = sha256(canonicalRequest);
        Optional<IdempotencyKeys.Stored> previous = keys.reserve(tenantId, idempotencyKey, hash);
        if (previous.isPresent()) {
            IdempotencyKeys.Stored stored = previous.get();
            if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
                throw new DomainException("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different sale");
            }
            if (stored.resourceId() == null) {
                throw new DomainException("IDEMPOTENCY_KEY_IN_PROGRESS", "The sale with this key is still being processed");
            }
            return invoices.findById(tenantId, stored.resourceId())
                    .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        }
        Invoice draft = drafts.create(tenantId, actor,
                new InvoiceDraftService.DraftCommand(DocumentKind.POS_EQUIVALENT, command.customerId(), command.lines()));
        Invoice issued = finalizer.finalizeInvoice(tenantId, actor, draft.id());
        keys.complete(tenantId, idempotencyKey, issued.id());
        return issued;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
