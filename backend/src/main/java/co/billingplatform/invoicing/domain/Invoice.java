package co.billingplatform.invoicing.domain;

import co.billingplatform.electronicinvoicing.domain.ElectronicDocument.NumberingAuthorization;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Agregado factura. Protege sus invariantes:
 * <ul>
 *   <li>solo es editable en borrador;</li>
 *   <li>los totales siempre se derivan del motor de cálculo (nunca se aceptan del cliente);</li>
 *   <li>solo se valida con evidencia del proveedor;</li>
 *   <li>al finalizarse queda ligada a la cuenta de proveedor activa y a su ambiente; la
 *       numeración usada debe ser del mismo ambiente, y cambiar de proveedor después no deja
 *       documentos huérfanos;</li>
 *   <li>una validación reportada en un ambiente distinto al de emisión se rechaza;</li>
 *   <li>pertenece a un único tenant, inmutable.</li>
 * </ul>
 */
public final class Invoice {

    public static final String CURRENCY_COP = "COP";

    public record StatusChange(InvoiceStatus from, InvoiceStatus to, Instant at, String detail) {}

    private final UUID id;
    private final TenantId tenantId;
    private final DocumentKind kind;
    private final String currency;
    private Party customer;
    private List<InvoiceLine> lines;
    private LocalDate issueDate;
    private DocumentCalculator.Result calculation;
    private InvoiceStatus status;
    private DocumentNumber number;
    private NumberingAuthorization authorization;
    private UUID numberingRangeId;
    private UUID providerAccountId;
    private ProviderEnvironment environment;
    private ExternalReference externalReference;
    private ValidationEvidence evidence;
    private List<String> rejectionReasons = List.of();
    private boolean outcomeUncertain;
    private int providerInteractions;
    private String lastError;
    private final List<StatusChange> history = new ArrayList<>();

    private Invoice(UUID id, TenantId tenantId, DocumentKind kind, String currency) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.kind = Objects.requireNonNull(kind);
        if (kind != DocumentKind.INVOICE && kind != DocumentKind.POS_EQUIVALENT) {
            throw new DomainException("INVALID_DOCUMENT_KIND", "Sales documents are invoices or POS equivalents");
        }
        this.currency = Objects.requireNonNull(currency);
    }

    public static Invoice draft(UUID id, TenantId tenantId, Party customer, List<InvoiceLine> lines,
                                LocalDate today, TaxCatalog taxes, DocumentCalculator calculator, Instant now) {
        return draft(id, tenantId, DocumentKind.INVOICE, customer, lines, today, taxes, calculator, now);
    }

    /** @param kind factura de venta o documento equivalente POS: mismo ciclo de vida, numeración propia. */
    public static Invoice draft(UUID id, TenantId tenantId, DocumentKind kind, Party customer, List<InvoiceLine> lines,
                                LocalDate today, TaxCatalog taxes, DocumentCalculator calculator, Instant now) {
        Invoice invoice = new Invoice(id, tenantId, kind, CURRENCY_COP);
        invoice.status = InvoiceStatus.DRAFT;
        invoice.applyContent(customer, lines, today, taxes, calculator);
        invoice.history.add(new StatusChange(null, InvoiceStatus.DRAFT, now, "created"));
        return invoice;
    }

    /** Estado persistido de una factura, para reconstruirla desde la base de datos. */
    public record Snapshot(UUID id, TenantId tenantId, Party customer, List<InvoiceLine> lines, LocalDate issueDate,
                           InvoiceStatus status, DocumentNumber number, NumberingAuthorization authorization,
                           UUID numberingRangeId, UUID providerAccountId, ProviderEnvironment environment, ExternalReference externalReference,
                           ValidationEvidence evidence, List<String> rejectionReasons, boolean outcomeUncertain,
                           int providerInteractions, String lastError, List<StatusChange> history,
                           java.math.BigDecimal storedPayableAmount, DocumentKind kind) {}

    /**
     * Reconstruye una factura persistida. Los totales se recalculan con el motor y se comparan
     * con los guardados: si no coinciden, el registro fue alterado o el cálculo cambió, y la
     * factura no se carga (falla segura en vez de transmitir montos distintos a los guardados).
     */
    public static Invoice restore(Snapshot s, TaxCatalog taxes, DocumentCalculator calculator) {
        Invoice invoice = new Invoice(s.id(), s.tenantId(), s.kind() == null ? DocumentKind.INVOICE : s.kind(), CURRENCY_COP);
        invoice.applyContent(s.customer(), s.lines(), s.issueDate(), taxes, calculator);
        if (s.storedPayableAmount() != null
                && invoice.calculation.payableAmount().compareTo(s.storedPayableAmount()) != 0) {
            throw new IllegalStateException("Stored totals of invoice " + s.id() + " do not match recalculation");
        }
        invoice.status = Objects.requireNonNull(s.status());
        invoice.number = s.number();
        invoice.authorization = s.authorization();
        invoice.numberingRangeId = s.numberingRangeId();
        invoice.providerAccountId = s.providerAccountId();
        invoice.environment = s.environment();
        invoice.externalReference = s.externalReference();
        invoice.evidence = s.evidence();
        invoice.rejectionReasons = s.rejectionReasons() == null ? List.of() : List.copyOf(s.rejectionReasons());
        invoice.outcomeUncertain = s.outcomeUncertain();
        invoice.providerInteractions = s.providerInteractions();
        invoice.lastError = s.lastError();
        invoice.history.addAll(s.history() == null ? List.of() : s.history());
        return invoice;
    }

    public void replaceContent(Party customer, List<InvoiceLine> lines, LocalDate today,
                               TaxCatalog taxes, DocumentCalculator calculator) {
        requireStatus(InvoiceStatus.DRAFT);
        applyContent(customer, lines, today, taxes, calculator);
    }

    /**
     * Asigna número y fecha de emisión y congela el contenido. Los tributos se recalculan
     * con las tarifas vigentes en la fecha de emisión real.
     */
    public void finalizeWith(NumberingRange range, ProviderAccount account, LocalDate issueDate,
                             TaxCatalog taxes, DocumentCalculator calculator, Instant now) {
        requireStatus(InvoiceStatus.DRAFT);
        Objects.requireNonNull(account);
        if (!range.tenantId().equals(tenantId)) {
            throw new DomainException("NUMBERING_RANGE_NOT_FOUND", "Numbering range does not belong to this organization");
        }
        if (!account.tenantId().equals(tenantId)) {
            throw new DomainException("PROVIDER_ACCOUNT_NOT_FOUND", "Provider account does not belong to this organization");
        }
        if (range.documentKind() != kind) {
            throw new DomainException("NUMBERING_RANGE_NOT_FOUND",
                    "Numbering resolution is for %s, not for %s".formatted(range.documentKind(), kind));
        }
        if (range.environment() != account.environment()) {
            throw new DomainException("NUMBERING_ENVIRONMENT_MISMATCH",
                    "Numbering resolution is for %s but the provider account operates in %s"
                            .formatted(range.environment(), account.environment()));
        }
        applyContent(customer, lines, issueDate, taxes, calculator);
        if (calculation.payableAmount().signum() <= 0) {
            throw new DomainException("INVOICE_TOTAL_NOT_POSITIVE", "Invoice total must be greater than zero");
        }
        this.number = range.allocate(issueDate);
        this.authorization = range.authorization();
        this.numberingRangeId = range.id();
        this.providerAccountId = account.id();
        this.environment = account.environment();
        transition(InvoiceStatus.PENDING_SUBMISSION, now, "finalized as " + number.formatted() + " (" + environment + ")");
    }

    /** Aplica un resultado normalizado del proveedor (envío, consulta o webhook). Idempotente ante repeticiones. */
    public void recordProviderResult(SubmissionResult result, Instant now) {
        Objects.requireNonNull(result);
        if (status.isTerminal()) {
            ensureConsistentWithTerminalState(result);
            return;
        }
        if (status != InvoiceStatus.PENDING_SUBMISSION && status != InvoiceStatus.SUBMITTED) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Invoice is not awaiting a provider result");
        }
        providerInteractions++;
        switch (result) {
            case SubmissionResult.Accepted a -> {
                if (a.evidence().environment() != environment) {
                    throw new DomainException("CONFLICTING_PROVIDER_RESULT",
                            "Validation reported for %s but the invoice was issued in %s"
                                    .formatted(a.evidence().environment(), environment));
                }
                this.externalReference = a.reference();
                this.evidence = a.evidence();
                this.outcomeUncertain = false;
                this.lastError = null;
                transition(InvoiceStatus.ACCEPTED, now, "validated (" + a.evidence().environment() + ")");
            }
            case SubmissionResult.Pending p -> {
                this.externalReference = p.reference();
                this.outcomeUncertain = false;
                this.lastError = null;
                transition(InvoiceStatus.SUBMITTED, now, "received by provider");
            }
            case SubmissionResult.Rejected r -> {
                if (r.reference() != null) {
                    this.externalReference = r.reference();
                }
                this.rejectionReasons = r.reasons();
                this.outcomeUncertain = false;
                transition(InvoiceStatus.REJECTED, now, "rejected");
            }
            case SubmissionResult.TransientFailure t -> {
                this.lastError = t.reason();
                if (t.requestMayHaveBeenReceived() && externalReference == null) {
                    this.outcomeUncertain = true;
                    transition(InvoiceStatus.SUBMITTED, now, "outcome uncertain: " + t.reason());
                }
            }
            case SubmissionResult.PermanentFailure f -> {
                this.lastError = f.reason();
                transition(InvoiceStatus.FAILED, now, "failed: " + f.reason());
            }
        }
    }

    /**
     * Factura electrónica con validez tributaria: validada con evidencia en PRODUCTION.
     * Un documento validado en simulación o habilitación nunca se presenta como tal.
     */
    public boolean isLegallyValid() {
        return status == InvoiceStatus.ACCEPTED && evidence != null && evidence.environment().hasLegalEffect();
    }

    private void ensureConsistentWithTerminalState(SubmissionResult result) {
        boolean consistent = switch (result) {
            case SubmissionResult.Accepted a -> status == InvoiceStatus.ACCEPTED
                    && evidence.documentKey().equals(a.evidence().documentKey());
            case SubmissionResult.Rejected r -> status == InvoiceStatus.REJECTED;
            case SubmissionResult.Pending p -> status == InvoiceStatus.ACCEPTED || status == InvoiceStatus.REJECTED;
            case SubmissionResult.TransientFailure t -> true;
            case SubmissionResult.PermanentFailure f -> status == InvoiceStatus.FAILED;
        };
        if (!consistent) {
            throw new DomainException("CONFLICTING_PROVIDER_RESULT",
                    "Provider result conflicts with final state " + status + "; manual review required");
        }
    }

    private void applyContent(Party customer, List<InvoiceLine> lines, LocalDate date,
                              TaxCatalog taxes, DocumentCalculator calculator) {
        Objects.requireNonNull(customer, "customer is required");
        Objects.requireNonNull(date);
        if (lines == null || lines.isEmpty()) {
            throw new DomainException("DOCUMENT_WITHOUT_LINES", "An invoice needs at least one line");
        }
        if (lines.size() > 500) {
            throw new DomainException("TOO_MANY_LINES", "An invoice supports at most 500 lines");
        }
        List<DocumentCalculator.LineInput> inputs = lines.stream()
                .map(l -> new DocumentCalculator.LineInput(
                        l.quantity(), l.unitPrice(), l.discount(),
                        l.taxRateCodes().stream().map(code -> taxes.resolve(code, date)).toList()))
                .toList();
        this.calculation = calculator.calculate(inputs);
        this.customer = customer;
        this.lines = List.copyOf(lines);
        this.issueDate = date;
    }

    private void transition(InvoiceStatus to, Instant now, String detail) {
        if (to == status) {
            return;
        }
        history.add(new StatusChange(status, to, now, detail));
        this.status = to;
    }

    private void requireStatus(InvoiceStatus expected) {
        if (status != expected) {
            throw new DomainException("INVALID_STATE_TRANSITION",
                    "Operation requires status %s but invoice is %s".formatted(expected, status));
        }
    }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public DocumentKind kind() { return kind; }
    public String currency() { return currency; }
    public Party customer() { return customer; }
    public List<InvoiceLine> lines() { return lines; }
    public LocalDate issueDate() { return issueDate; }
    public DocumentCalculator.Result calculation() { return calculation; }
    public InvoiceStatus status() { return status; }
    public Optional<DocumentNumber> number() { return Optional.ofNullable(number); }
    public Optional<NumberingAuthorization> authorization() { return Optional.ofNullable(authorization); }
    public Optional<UUID> numberingRangeId() { return Optional.ofNullable(numberingRangeId); }
    public Optional<UUID> providerAccountId() { return Optional.ofNullable(providerAccountId); }
    /** Ambiente en que se emitió (fijado al finalizar). Vacío en borrador. */
    public Optional<ProviderEnvironment> environment() { return Optional.ofNullable(environment); }
    public Optional<ExternalReference> externalReference() { return Optional.ofNullable(externalReference); }
    public Optional<ValidationEvidence> evidence() { return Optional.ofNullable(evidence); }
    public List<String> rejectionReasons() { return rejectionReasons; }
    public boolean outcomeUncertain() { return outcomeUncertain; }
    public int providerInteractions() { return providerInteractions; }
    public Optional<String> lastError() { return Optional.ofNullable(lastError); }
    public List<StatusChange> history() { return List.copyOf(history); }
}
