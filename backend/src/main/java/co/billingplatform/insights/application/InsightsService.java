package co.billingplatform.insights.application;

import co.billingplatform.insights.domain.Attention;
import co.billingplatform.insights.domain.AttentionRules;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Resumen del inicio: ventas del mes y pendientes. Lectura pura: no cambia nada. */
public final class InsightsService {

    public record DailyTotal(LocalDate date, BigDecimal total) {}

    public record Sales(BigDecimal total, int documents, BigDecimal averageTicket, BigDecimal credited) {}

    /** Cuota del ciclo actual: límite ({@code null} = sin límite) y usados. La provee el módulo de suscripciones. */
    public record Quota(Integer limit, int used, boolean trial) {}

    public interface QuotaReader {
        Quota quota(TenantId tenantId);
    }

    /** Hechos de la organización (cada adaptador los lee de su módulo). */
    public interface FactsReader {
        /** @param includePlan si el usuario puede ver el plan y la cuota */
        AttentionRules.Facts facts(TenantId tenantId, LocalDate today, boolean includePlan);

        /** Documentos aceptados en el ambiente, menos notas crédito aceptadas, por día. */
        List<DailyTotal> dailySales(TenantId tenantId, String environment, LocalDate from, LocalDate toInclusive);

        int acceptedSalesDocuments(TenantId tenantId, String environment, LocalDate from, LocalDate toInclusive);

        BigDecimal credited(TenantId tenantId, String environment, LocalDate from, LocalDate toInclusive);
    }

    public record Dashboard(String environment, LocalDate from, LocalDate to, Sales sales, List<DailyTotal> daily,
                            List<Attention> attention) {}

    private final FactsReader reader;
    private final Clock clock;

    public InsightsService(FactsReader reader, Clock clock) {
        this.reader = Objects.requireNonNull(reader);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<Attention> attention(TenantId tenantId, boolean includePlan) {
        LocalDate today = ColombianTime.today(clock);
        return AttentionRules.evaluate(reader.facts(tenantId, today, includePlan), today);
    }

    public Dashboard dashboard(TenantId tenantId, boolean includePlan) {
        LocalDate today = ColombianTime.today(clock);
        AttentionRules.Facts facts = reader.facts(tenantId, today, includePlan);
        LocalDate from = today.withDayOfMonth(1);
        String env = facts.activeEnvironment();
        List<DailyTotal> daily = env == null ? List.of() : reader.dailySales(tenantId, env, from, today);
        int docs = env == null ? 0 : reader.acceptedSalesDocuments(tenantId, env, from, today);
        BigDecimal credited = env == null ? BigDecimal.ZERO : reader.credited(tenantId, env, from, today);
        BigDecimal total = daily.stream().map(DailyTotal::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = docs == 0 ? BigDecimal.ZERO
                : total.add(credited).divide(BigDecimal.valueOf(docs), 2, RoundingMode.HALF_UP);
        return new Dashboard(env, from, today, new Sales(total, docs, average, credited), daily,
                AttentionRules.evaluate(facts, today));
    }
}
