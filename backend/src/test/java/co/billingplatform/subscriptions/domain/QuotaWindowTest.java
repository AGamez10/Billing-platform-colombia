package co.billingplatform.subscriptions.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class QuotaWindowTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    @DisplayName("ciclos mensuales anclados al día de inicio")
    void monthlyCycles() {
        assertEquals(new QuotaWindow(d("2026-11-15"), d("2026-12-15")), QuotaWindow.monthlyCycle(d("2026-01-15"), d("2026-11-20")));
        assertEquals(new QuotaWindow(d("2026-11-15"), d("2026-12-15")), QuotaWindow.monthlyCycle(d("2026-01-15"), d("2026-11-15")));
        assertEquals(new QuotaWindow(d("2026-10-15"), d("2026-11-15")), QuotaWindow.monthlyCycle(d("2026-01-15"), d("2026-11-14")));
        assertEquals(new QuotaWindow(d("2026-01-15"), d("2026-02-15")), QuotaWindow.monthlyCycle(d("2026-01-15"), d("2026-01-15")));
    }

    @Test
    @DisplayName("inicio el 31: en meses cortos el ciclo empieza el último día, sin desplazarse")
    void endOfMonthAnchor() {
        assertEquals(new QuotaWindow(d("2026-02-28"), d("2026-03-31")), QuotaWindow.monthlyCycle(d("2026-01-31"), d("2026-03-10")));
        assertEquals(new QuotaWindow(d("2026-03-31"), d("2026-04-30")), QuotaWindow.monthlyCycle(d("2026-01-31"), d("2026-04-01")));
    }

    @Test
    @DisplayName("propiedad: todo día cae en exactamente un ciclo de un mes")
    void everyDayBelongsToItsCycle() {
        LocalDate anchor = d("2025-01-31");
        for (LocalDate day = anchor; day.isBefore(d("2028-01-01")); day = day.plusDays(1)) {
            QuotaWindow w = QuotaWindow.monthlyCycle(anchor, day);
            assertFalse(day.isBefore(w.start()));
            assertTrue(day.isBefore(w.endExclusive()));
        }
        assertThrows(IllegalArgumentException.class, () -> QuotaWindow.monthlyCycle(anchor, d("2025-01-30")));
    }
}
