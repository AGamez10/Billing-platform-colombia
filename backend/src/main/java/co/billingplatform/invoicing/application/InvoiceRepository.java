package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.shared.domain.TenantId;

import java.util.Optional;
import java.util.UUID;

/**
 * Puerto de persistencia de facturas. El tenant es parámetro obligatorio de toda
 * lectura: no existe un "findById" global (defensa contra IDOR/BOLA). La
 * implementación además corre bajo Row-Level Security (docs/MULTITENANCY.md).
 */
public interface InvoiceRepository {

    Optional<Invoice> findById(TenantId tenantId, UUID invoiceId);

    Optional<Invoice> findByExternalReference(TenantId tenantId, ExternalReference reference);

    void save(Invoice invoice);
}
