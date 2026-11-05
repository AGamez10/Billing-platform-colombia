package co.billingplatform.products.application;

import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.TenantId;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    /** @throws DomainException {@code PRODUCT_SKU_ALREADY_EXISTS} si el SKU ya existe en la organización */
    void insert(Product product);

    /** Guarda solo si la versión almacenada es {@code product.version() - 1}; {@code false} si hubo conflicto. */
    boolean update(Product product);

    Optional<Product> findById(TenantId tenantId, UUID id);

    Page<Product> search(TenantId tenantId, String query, boolean includeInactive, int limit, int offset);
}
