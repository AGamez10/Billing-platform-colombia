package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registro de resoluciones de numeración. Los datos los transcribe el cliente desde su
 * resolución de la DIAN; la plataforma no los inventa ni los consulta a la DIAN.
 * La resolución de PRODUCTION solo se usa con una cuenta de proveedor en PRODUCTION.
 */
public final class NumberingRangeService {

    public record NewRange(ProviderEnvironment environment, String resolutionNumber, String prefix,
                           long rangeFrom, long rangeTo, LocalDate validFrom, LocalDate validTo) {}

    private final NumberingRangeRepository ranges;
    private final AuditTrail audit;

    public NumberingRangeService(NumberingRangeRepository ranges, AuditTrail audit) {
        this.ranges = Objects.requireNonNull(ranges);
        this.audit = Objects.requireNonNull(audit);
    }

    public NumberingRange register(TenantId tenantId, Actor actor, NewRange r) {
        Objects.requireNonNull(r.environment(), "environment is required");
        NumberingRange range = NumberingRange.create(tenantId, r.environment(), r.resolutionNumber(),
                r.prefix() == null ? "" : r.prefix().strip().toUpperCase(java.util.Locale.ROOT),
                r.rangeFrom(), r.rangeTo(), r.validFrom(), r.validTo());
        ranges.add(range);
        audit.record(new AuditTrail.Event(tenantId, actor, "NUMBERING_RANGE_REGISTERED", "numbering_range",
                range.id().toString(), Map.of("environment", range.environment().name(),
                        "resolution", range.resolutionNumber(), "prefix", range.prefix(),
                        "from", Long.toString(range.rangeFrom()), "to", Long.toString(range.rangeTo()))));
        return range;
    }

    public List<NumberingRange> list(TenantId tenantId) {
        return ranges.list(tenantId);
    }
}
