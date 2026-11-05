package co.billingplatform.invoicing.application;

import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Casos de uso de borradores. Cada método público es una transacción (la frontera la pone
 * la capa de infraestructura). Los totales siempre los calcula el servidor.
 */
public final class InvoiceDraftService {

    public record DraftCommand(UUID customerId, List<InvoiceLine> lines) {
        public DraftCommand {
            Objects.requireNonNull(customerId, "customerId is required");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines are required"));
        }
    }

    private final InvoiceRepository invoices;
    private final CustomerDirectory customers;
    private final TaxCatalog taxes;
    private final DocumentCalculator calculator;
    private final AuditTrail audit;
    private final Clock clock;

    public InvoiceDraftService(InvoiceRepository invoices, CustomerDirectory customers, TaxCatalog taxes,
                               DocumentCalculator calculator, AuditTrail audit, Clock clock) {
        this.invoices = Objects.requireNonNull(invoices);
        this.customers = Objects.requireNonNull(customers);
        this.taxes = Objects.requireNonNull(taxes);
        this.calculator = Objects.requireNonNull(calculator);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Invoice create(TenantId tenantId, Actor actor, DraftCommand command) {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), tenantId, customer(tenantId, command.customerId()),
                command.lines(), ColombianTime.today(clock), taxes, calculator, clock.instant());
        invoices.save(invoice);
        audit.record(new AuditTrail.Event(tenantId, actor, "INVOICE_DRAFT_CREATED", "invoice",
                invoice.id().toString(), Map.of("customerId", command.customerId().toString())));
        return invoice;
    }

    public Invoice update(TenantId tenantId, Actor actor, UUID invoiceId, DraftCommand command) {
        Invoice invoice = load(tenantId, invoiceId);
        invoice.replaceContent(customer(tenantId, command.customerId()), command.lines(),
                ColombianTime.today(clock), taxes, calculator);
        invoices.save(invoice);
        audit.record(new AuditTrail.Event(tenantId, actor, "INVOICE_DRAFT_UPDATED", "invoice",
                invoiceId.toString(), Map.of()));
        return invoice;
    }

    public void delete(TenantId tenantId, Actor actor, UUID invoiceId) {
        Invoice invoice = load(tenantId, invoiceId);
        if (invoice.status() != InvoiceStatus.DRAFT) {
            throw new DomainException("INVALID_STATE_TRANSITION",
                    "Only drafts can be deleted; issued invoices are adjusted with credit notes");
        }
        invoices.deleteDraft(tenantId, invoiceId);
        audit.record(new AuditTrail.Event(tenantId, actor, "INVOICE_DRAFT_DELETED", "invoice",
                invoiceId.toString(), Map.of()));
    }

    private Invoice load(TenantId tenantId, UUID invoiceId) {
        return invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
    }

    private Party customer(TenantId tenantId, UUID customerId) {
        return customers.findActive(tenantId, customerId)
                .orElseThrow(() -> new DomainException("CUSTOMER_NOT_FOUND", "Customer not found"));
    }
}
