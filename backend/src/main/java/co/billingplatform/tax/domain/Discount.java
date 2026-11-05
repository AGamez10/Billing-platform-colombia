package co.billingplatform.tax.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Descuento comercial de línea: porcentaje sobre el bruto o valor fijo. */
public sealed interface Discount {

    Discount NONE = new Amount(BigDecimal.ZERO);

    /** Valor del descuento, sin redondear, para un bruto dado. */
    BigDecimal amountFor(BigDecimal gross);

    record Percentage(BigDecimal percent) implements Discount {
        public Percentage {
            Objects.requireNonNull(percent);
            if (percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("discount percent must be between 0 and 100");
            }
        }

        @Override
        public BigDecimal amountFor(BigDecimal gross) {
            return gross.multiply(percent.movePointLeft(2));
        }
    }

    record Amount(BigDecimal value) implements Discount {
        public Amount {
            Objects.requireNonNull(value);
            if (value.signum() < 0) {
                throw new IllegalArgumentException("discount amount must not be negative");
            }
        }

        @Override
        public BigDecimal amountFor(BigDecimal gross) {
            return value;
        }
    }
}
