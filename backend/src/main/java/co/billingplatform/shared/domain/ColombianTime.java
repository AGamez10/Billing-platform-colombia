package co.billingplatform.shared.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Las fechas de negocio (emisión, periodos) se determinan en hora de Colombia, no en la zona
 * del servidor: un VPS en UTC emitiría con fecha del día siguiente entre las 19:00 y la
 * medianoche de Bogotá.
 */
public final class ColombianTime {

    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private ColombianTime() {}

    public static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZONE);
    }
}
