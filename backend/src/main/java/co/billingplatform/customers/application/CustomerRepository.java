package co.billingplatform.customers.application;

import co.billingplatform.customers.domain.Customer;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.TenantId;

import java.util.Optional;
import java.util.UUID;

/** Persistencia de clientes. Toda operación va acotada por tenant (y bajo RLS). */
public interface CustomerRepository {

    /** @throws DomainException {@code CUSTOMER_ALREADY_EXISTS} si ya hay un cliente con esa identificación */
    void insert(Customer customer);

    /**
     * Guarda la nueva versión solo si la almacenada tiene {@code customer.version() - 1}.
     *
     * @return {@code false} si otro usuario la modificó antes (conflicto de versión)
     */
    boolean update(Customer customer);

    Optional<Customer> findById(TenantId tenantId, UUID id);

    /** Búsqueda por nombre o número de identificación; {@code query} vacío lista todos. */
    Page<Customer> search(TenantId tenantId, String query, boolean includeInactive, int limit, int offset);
}
