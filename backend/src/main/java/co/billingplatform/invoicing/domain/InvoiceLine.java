package co.billingplatform.invoicing.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.tax.domain.Discount;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Línea de factura tal como la define el usuario. Los tributos se referencian por
 * código de tarifa; la tarifa concreta se resuelve por fecha de emisión.
 *
 * @param productId producto del catálogo del tenant del que proviene la línea, si aplica
 * @param unitCode  unidad de medida (código del catálogo de unidades; validación de
 *                  catálogo pendiente del adaptador real)
 */
public record InvoiceLine(
        UUID productId,
        String description,
        String unitCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        Discount discount,
        List<String> taxRateCodes) {

    public InvoiceLine {
        if (description == null || description.isBlank()) {
            throw new DomainException("INVALID_LINE", "Line description is required");
        }
        description = description.strip();
        if (description.length() > 300) {
            throw new DomainException("INVALID_LINE", "Line description is too long");
        }
        if (unitCode == null || !unitCode.matches("[A-Za-z0-9]{1,10}")) {
            throw new DomainException("INVALID_LINE", "Unit code is not valid");
        }
        Objects.requireNonNull(quantity);
        Objects.requireNonNull(unitPrice);
        if (quantity.scale() > 6 || unitPrice.scale() > 6) {
            throw new DomainException("INVALID_LINE", "At most 6 decimals in quantity and unit price");
        }
        discount = discount == null ? Discount.NONE : discount;
        taxRateCodes = List.copyOf(taxRateCodes == null ? List.of() : taxRateCodes);
    }
}
