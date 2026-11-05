package co.billingplatform.customers.application;

import co.billingplatform.customers.domain.Customer;
import co.billingplatform.invoicing.application.CustomerDirectory;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Casos de uso de clientes. Además implementa {@link CustomerDirectory}, el puerto con el que
 * facturación toma el snapshot del cliente al crear una factura.
 */
public final class CustomerService implements CustomerDirectory {

    public record Details(IdentificationType identificationType, String identificationNumber, Integer checkDigit,
                          String legalName, String email, String phone, String address, String cityCode) {}

    private final CustomerRepository customers;
    private final AuditTrail audit;

    public CustomerService(CustomerRepository customers, AuditTrail audit) {
        this.customers = Objects.requireNonNull(customers);
        this.audit = Objects.requireNonNull(audit);
    }

    public Customer create(TenantId tenantId, Actor actor, Details d) {
        Customer customer = Customer.create(tenantId, identification(d), d.legalName(), d.email(),
                d.phone(), d.address(), d.cityCode());
        customers.insert(customer);
        audit.record(new AuditTrail.Event(tenantId, actor, "CUSTOMER_CREATED", "customer", customer.id().toString(), Map.of()));
        return customer;
    }

    public Customer update(TenantId tenantId, Actor actor, UUID id, int expectedVersion, Details d) {
        Customer current = get(tenantId, id);
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        Customer updated = current.withDetails(identification(d), d.legalName(), d.email(), d.phone(), d.address(), d.cityCode());
        if (!customers.update(updated)) {
            throw versionConflict();
        }
        audit.record(new AuditTrail.Event(tenantId, actor, "CUSTOMER_UPDATED", "customer", id.toString(), Map.of()));
        return updated;
    }

    /** Los clientes no se borran (las facturas los referencian): se desactivan. */
    public Customer deactivate(TenantId tenantId, Actor actor, UUID id) {
        Customer current = get(tenantId, id);
        if (!current.active()) {
            return current;
        }
        Customer updated = current.deactivated();
        if (!customers.update(updated)) {
            throw versionConflict();
        }
        audit.record(new AuditTrail.Event(tenantId, actor, "CUSTOMER_DEACTIVATED", "customer", id.toString(), Map.of()));
        return updated;
    }

    public Customer get(TenantId tenantId, UUID id) {
        return customers.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("CUSTOMER_NOT_FOUND", "Customer not found"));
    }

    public Page<Customer> search(TenantId tenantId, String query, boolean includeInactive, Integer limit, Integer offset) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 100) {
            throw new DomainException("INVALID_QUERY", "Search text is too long");
        }
        return customers.search(tenantId, q, includeInactive, Page.clampLimit(limit), Page.clampOffset(offset));
    }

    @Override
    public Optional<Party> findActive(TenantId tenantId, UUID customerId) {
        return customers.findById(tenantId, customerId).filter(Customer::active).map(Customer::asParty);
    }

    private static PartyIdentification identification(Details d) {
        if (d.identificationType() == null) {
            throw new DomainException("INVALID_IDENTIFICATION", "Identification type is required");
        }
        return new PartyIdentification(d.identificationType(), d.identificationNumber(), d.checkDigit());
    }

    private static DomainException versionConflict() {
        return new DomainException("VERSION_CONFLICT", "The record was modified by someone else; reload and try again");
    }
}
