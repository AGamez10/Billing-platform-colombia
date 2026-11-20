package co.billingplatform.invoicing.domain;

import co.billingplatform.electronicinvoicing.domain.ElectronicDocument.NumberingAuthorization;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Rango de numeración autorizado por una resolución de la DIAN para un tenant.
 *
 * <p>Los números se asignan al finalizar la factura, nunca al crear el borrador, para
 * que borradores descartados no generen huecos. En persistencia la asignación es
 * atómica ({@code UPDATE ... SET next_number = next_number + 1 ... RETURNING} con bloqueo
 * de fila), así dos facturas concurrentes nunca reciben el mismo número.
 */
public final class NumberingRange {

    private final UUID id;
    private final TenantId tenantId;
    private final DocumentKind documentKind;
    private final ProviderEnvironment environment;
    private final String resolutionNumber;
    private final String prefix;
    private final long rangeFrom;
    private final long rangeTo;
    private final LocalDate validFrom;
    private final LocalDate validTo;
    private long nextNumber;

    public NumberingRange(UUID id, TenantId tenantId, ProviderEnvironment environment, String resolutionNumber,
                          String prefix, long rangeFrom, long rangeTo, LocalDate validFrom, LocalDate validTo,
                          long nextNumber) {
        this(id, tenantId, DocumentKind.INVOICE, environment, resolutionNumber, prefix, rangeFrom, rangeTo,
                validFrom, validTo, nextNumber);
    }

    /** @param documentKind tipo de documento que autoriza la resolución (factura o POS: cada uno tiene la suya). */
    public NumberingRange(UUID id, TenantId tenantId, DocumentKind documentKind, ProviderEnvironment environment,
                          String resolutionNumber, String prefix, long rangeFrom, long rangeTo, LocalDate validFrom,
                          LocalDate validTo, long nextNumber) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.documentKind = Objects.requireNonNull(documentKind);
        if (documentKind == DocumentKind.DEBIT_NOTE) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Unsupported document kind for numbering: " + documentKind);
        }
        this.environment = Objects.requireNonNull(environment);
        if (resolutionNumber == null || resolutionNumber.isBlank()) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Resolution number is required");
        }
        // El prefijo puede ser vacío. Límite de longitud pendiente de confirmar con el Anexo Técnico.
        if (prefix == null || !prefix.matches("[A-Z0-9]{0,10}")) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Prefix must be uppercase alphanumeric");
        }
        if (rangeFrom <= 0 || rangeTo < rangeFrom) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Invalid authorized range");
        }
        Objects.requireNonNull(validFrom);
        Objects.requireNonNull(validTo);
        if (validTo.isBefore(validFrom)) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Invalid validity period");
        }
        if (nextNumber < rangeFrom || nextNumber > rangeTo + 1) {
            throw new DomainException("INVALID_NUMBERING_RANGE", "Next number outside authorized range");
        }
        this.resolutionNumber = resolutionNumber.strip();
        this.prefix = prefix;
        this.rangeFrom = rangeFrom;
        this.rangeTo = rangeTo;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.nextNumber = nextNumber;
    }

    /**
     * @param environment ambiente al que pertenece la resolución: una numeración de pruebas
     *                    nunca se usa en producción ni al revés.
     */
    public static NumberingRange create(TenantId tenantId, ProviderEnvironment environment, String resolutionNumber,
                                        String prefix, long rangeFrom, long rangeTo,
                                        LocalDate validFrom, LocalDate validTo) {
        return create(tenantId, DocumentKind.INVOICE, environment, resolutionNumber, prefix, rangeFrom, rangeTo,
                validFrom, validTo);
    }

    public static NumberingRange create(TenantId tenantId, DocumentKind documentKind, ProviderEnvironment environment,
                                        String resolutionNumber, String prefix, long rangeFrom, long rangeTo,
                                        LocalDate validFrom, LocalDate validTo) {
        return new NumberingRange(UUID.randomUUID(), tenantId, documentKind, environment, resolutionNumber, prefix,
                rangeFrom, rangeTo, validFrom, validTo, rangeFrom);
    }

    public DocumentNumber allocate(LocalDate issueDate) {
        Objects.requireNonNull(issueDate);
        if (issueDate.isBefore(validFrom) || issueDate.isAfter(validTo)) {
            throw new DomainException("NUMBERING_RESOLUTION_NOT_VALID",
                    "Numbering resolution %s is not valid on %s".formatted(resolutionNumber, issueDate));
        }
        if (nextNumber > rangeTo) {
            throw new DomainException("NUMBERING_RANGE_EXHAUSTED",
                    "Numbering range of resolution %s is exhausted".formatted(resolutionNumber));
        }
        return new DocumentNumber(prefix, nextNumber++);
    }

    public long remaining() {
        return rangeTo - nextNumber + 1;
    }

    public NumberingAuthorization authorization() {
        return new NumberingAuthorization(resolutionNumber, validFrom, validTo, rangeFrom, rangeTo);
    }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public ProviderEnvironment environment() { return environment; }
    public String prefix() { return prefix; }
    public DocumentKind documentKind() { return documentKind; }
    public String resolutionNumber() { return resolutionNumber; }
    public long rangeFrom() { return rangeFrom; }
    public long rangeTo() { return rangeTo; }
    public LocalDate validFrom() { return validFrom; }
    public LocalDate validTo() { return validTo; }
    public long nextNumber() { return nextNumber; }
}
