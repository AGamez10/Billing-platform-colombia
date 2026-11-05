package co.billingplatform.products.application;

import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.ColombianTime;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.TaxCatalog;
import co.billingplatform.tax.domain.TaxRate;
import co.billingplatform.tax.domain.TaxType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Casos de uso del catálogo de productos y servicios. */
public final class ProductService {

    public record Details(String sku, String name, Product.Kind kind, String unitCode, BigDecimal unitPrice,
                          List<String> taxRateCodes) {}

    private final ProductRepository products;
    private final TaxCatalog taxes;
    private final AuditTrail audit;
    private final Clock clock;

    public ProductService(ProductRepository products, TaxCatalog taxes, AuditTrail audit, Clock clock) {
        this.products = Objects.requireNonNull(products);
        this.taxes = Objects.requireNonNull(taxes);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Product create(TenantId tenantId, Actor actor, Details d) {
        validateTaxCodes(d.taxRateCodes());
        Product product = Product.create(tenantId, d.sku(), d.name(), d.kind(), d.unitCode(), d.unitPrice(), d.taxRateCodes());
        products.insert(product);
        audit.record(new AuditTrail.Event(tenantId, actor, "PRODUCT_CREATED", "product", product.id().toString(), Map.of()));
        return product;
    }

    public Product update(TenantId tenantId, Actor actor, UUID id, int expectedVersion, Details d) {
        Product current = get(tenantId, id);
        if (current.version() != expectedVersion) {
            throw versionConflict();
        }
        validateTaxCodes(d.taxRateCodes());
        Product updated = current.withDetails(d.sku(), d.name(), d.kind(), d.unitCode(), d.unitPrice(), d.taxRateCodes());
        if (!products.update(updated)) {
            throw versionConflict();
        }
        audit.record(new AuditTrail.Event(tenantId, actor, "PRODUCT_UPDATED", "product", id.toString(), Map.of()));
        return updated;
    }

    public Product deactivate(TenantId tenantId, Actor actor, UUID id) {
        Product current = get(tenantId, id);
        if (!current.active()) {
            return current;
        }
        Product updated = current.deactivated();
        if (!products.update(updated)) {
            throw versionConflict();
        }
        audit.record(new AuditTrail.Event(tenantId, actor, "PRODUCT_DEACTIVATED", "product", id.toString(), Map.of()));
        return updated;
    }

    public Product get(TenantId tenantId, UUID id) {
        return products.findById(tenantId, id)
                .orElseThrow(() -> new DomainException("PRODUCT_NOT_FOUND", "Product not found"));
    }

    public Page<Product> search(TenantId tenantId, String query, boolean includeInactive, Integer limit, Integer offset) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 100) {
            throw new DomainException("INVALID_QUERY", "Search text is too long");
        }
        return products.search(tenantId, q, includeInactive, Page.clampLimit(limit), Page.clampOffset(offset));
    }

    /** Cada código debe existir y estar vigente hoy, y no puede haber dos tarifas del mismo tributo. */
    private void validateTaxCodes(List<String> codes) {
        if (codes == null) {
            return;
        }
        LocalDate today = ColombianTime.today(clock);
        Set<TaxType> types = EnumSet.noneOf(TaxType.class);
        for (String code : codes) {
            TaxRate rate = taxes.resolve(code, today);
            if (!types.add(rate.type())) {
                throw new DomainException("DUPLICATE_TAX_TYPE", "More than one " + rate.type() + " rate for the product");
            }
        }
    }

    private static DomainException versionConflict() {
        return new DomainException("VERSION_CONFLICT", "The record was modified by someone else; reload and try again");
    }
}
