package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.shared.domain.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface NumberingRangeRepository {

    /**
     * Resolución de facturación activa del tenant, para el ambiente dado y vigente en la
     * fecha, BLOQUEADA hasta el fin de la transacción ({@code SELECT ... FOR UPDATE}): dos
     * finalizaciones concurrentes se serializan y nunca reciben el mismo número.
     */
    Optional<NumberingRange> lockActiveRange(TenantId tenantId, DocumentKind kind, ProviderEnvironment environment, LocalDate onDate);

    void save(NumberingRange range);

    /** Registra una resolución nueva (activa). */
    void add(NumberingRange range);

    List<NumberingRange> list(TenantId tenantId);
}
