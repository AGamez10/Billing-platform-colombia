package co.billingplatform.invoicing.domain;

import co.billingplatform.electronicinvoicing.domain.Correction;
import co.billingplatform.shared.domain.DomainException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que hace de un documento una nota crédito: la factura que corrige (copiada al crearla:
 * número, CUFE y fecha no cambian) y el motivo.
 *
 * @param lineOrigins para cada línea de la nota, el número de línea de la factura que acredita
 */
public record CreditNoteDetails(UUID creditedInvoiceId, Correction.Concept concept, String description,
                                String creditedNumber, String creditedDocumentKey, LocalDate creditedIssueDate,
                                List<Integer> lineOrigins) {

    public CreditNoteDetails {
        Objects.requireNonNull(creditedInvoiceId);
        Objects.requireNonNull(concept);
        description = description == null ? "" : description.strip().replaceAll("\\s+", " ");
        if (description.length() < 3 || description.length() > 500) {
            throw new DomainException("INVALID_CREDIT_NOTE", "Describe the reason (3 to 500 characters)");
        }
        Objects.requireNonNull(creditedNumber);
        Objects.requireNonNull(creditedDocumentKey);
        Objects.requireNonNull(creditedIssueDate);
        lineOrigins = List.copyOf(lineOrigins);
    }

    public Correction correction() {
        return new Correction(concept, description, creditedNumber, creditedDocumentKey, creditedIssueDate);
    }
}
