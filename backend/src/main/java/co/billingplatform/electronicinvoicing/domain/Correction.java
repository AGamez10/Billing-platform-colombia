package co.billingplatform.electronicinvoicing.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Corrección que hace una nota crédito sobre una factura validada. Modelo neutral: el código
 * DIAN del concepto lo asigna cada adaptador según su proveedor y el Anexo Técnico (la tabla
 * oficial está en la "Caja de herramientas" de la DIAN y se confirma con el proveedor).
 *
 * @param referencedNumber      número completo de la factura corregida (prefijo + consecutivo)
 * @param referencedDocumentKey CUFE de la factura corregida
 */
public record Correction(Concept concept, String description, String referencedNumber, String referencedDocumentKey,
                         LocalDate referencedIssueDate) {

    public enum Concept {
        /** Devolución parcial de bienes o no aceptación parcial del servicio. */
        RETURN,
        /** Anulación de la factura: se acredita la totalidad. */
        ANNULMENT
    }

    public Correction {
        Objects.requireNonNull(concept);
        Objects.requireNonNull(description);
        Objects.requireNonNull(referencedNumber);
        Objects.requireNonNull(referencedDocumentKey);
        Objects.requireNonNull(referencedIssueDate);
    }
}
