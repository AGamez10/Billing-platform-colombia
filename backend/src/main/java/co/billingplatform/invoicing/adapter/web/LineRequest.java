package co.billingplatform.invoicing.adapter.web;

import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.tax.domain.Discount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Línea de venta tal como llega por la API (facturas, POS y cobros comparten el formato). */
public record LineRequest(
        @NotBlank @Size(max = 300) String description,
        @NotBlank @Size(max = 10) String unitCode,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitPrice,
        @Valid DiscountRequest discount,
        @Size(max = 5) List<@NotBlank @Size(max = 30) String> taxRateCodes) {

    public enum DiscountKind { PERCENT, AMOUNT }

    public record DiscountRequest(@NotNull DiscountKind kind, @NotNull @DecimalMin("0") BigDecimal value) {
        Discount toDomain() {
            if (kind == DiscountKind.PERCENT) {
                if (value.compareTo(new BigDecimal("100")) > 0) {
                    throw new DomainException("INVALID_DISCOUNT", "Discount percent must be between 0 and 100");
                }
                return new Discount.Percentage(value);
            }
            return new Discount.Amount(value);
        }
    }

    public InvoiceLine toDomain() {
        return new InvoiceLine(null, description, unitCode, quantity, unitPrice,
                discount == null ? Discount.NONE : discount.toDomain(), taxRateCodes);
    }
}
