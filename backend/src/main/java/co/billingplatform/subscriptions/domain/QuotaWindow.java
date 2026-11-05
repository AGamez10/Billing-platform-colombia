package co.billingplatform.subscriptions.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Ventana de conteo de documentos: [start, endExclusive).
 *
 * <p>La cuota es mensual aunque el pago sea anual (p. ej. el plan Micro se paga por año pero
 * da 15 documentos por mes). Los ciclos se anclan al día de inicio de la suscripción; si ese
 * día no existe en un mes (31), el ciclo empieza el último día de ese mes.
 */
public record QuotaWindow(LocalDate start, LocalDate endExclusive) {

    public QuotaWindow {
        Objects.requireNonNull(start);
        Objects.requireNonNull(endExclusive);
        if (!endExclusive.isAfter(start)) {
            throw new IllegalArgumentException("empty quota window");
        }
    }

    public static QuotaWindow monthlyCycle(LocalDate anchor, LocalDate today) {
        if (today.isBefore(anchor)) {
            throw new IllegalArgumentException("date before subscription start");
        }
        long n = ChronoUnit.MONTHS.between(anchor, today);
        while (anchor.plusMonths(n).isAfter(today)) {
            n--;
        }
        while (!anchor.plusMonths(n + 1).isAfter(today)) {
            n++;
        }
        return new QuotaWindow(anchor.plusMonths(n), anchor.plusMonths(n + 1));
    }
}
