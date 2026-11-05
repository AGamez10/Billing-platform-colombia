package co.billingplatform.products.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Producto o servicio del catálogo de una organización. Los tributos se referencian por
 * código de tarifa (p. ej. {@code IVA_19}); la tarifa se resuelve al facturar según la fecha.
 */
public record Product(
        UUID id,
        TenantId tenantId,
        String sku,
        String name,
        Kind kind,
        String unitCode,
        BigDecimal unitPrice,
        List<String> taxRateCodes,
        boolean active,
        int version) {

    public enum Kind { PRODUCT, SERVICE }

    public Product {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        if (kind == null) {
            throw new DomainException("INVALID_PRODUCT", "Kind is required (PRODUCT or SERVICE)");
        }
        if (name == null || name.isBlank() || name.strip().length() > 300) {
            throw new DomainException("INVALID_PRODUCT", "Name is required (max 300 characters)");
        }
        name = name.strip();
        if (sku != null && sku.isBlank()) {
            sku = null;
        }
        if (sku != null && !sku.strip().matches("^[A-Za-z0-9._\\-]{1,50}$")) {
            throw new DomainException("INVALID_PRODUCT", "SKU may contain letters, digits, dot, dash and underscore (max 50)");
        }
        sku = sku == null ? null : sku.strip();
        if (unitCode == null || !unitCode.matches("[A-Za-z0-9]{1,10}")) {
            throw new DomainException("INVALID_PRODUCT", "Unit code is not valid");
        }
        if (unitPrice == null || unitPrice.signum() < 0 || unitPrice.scale() > 6
                || unitPrice.compareTo(new BigDecimal("999999999999")) > 0) {
            throw new DomainException("INVALID_PRODUCT", "Unit price must be between 0 and 999,999,999,999 with at most 6 decimals");
        }
        taxRateCodes = List.copyOf(taxRateCodes == null ? List.of() : taxRateCodes);
        if (taxRateCodes.size() > 5 || taxRateCodes.stream().distinct().count() != taxRateCodes.size()) {
            throw new DomainException("INVALID_PRODUCT", "Tax rate codes must be distinct (max 5)");
        }
    }

    public static Product create(TenantId tenantId, String sku, String name, Kind kind, String unitCode,
                                 BigDecimal unitPrice, List<String> taxRateCodes) {
        return new Product(UUID.randomUUID(), tenantId, sku, name, kind, unitCode, unitPrice, taxRateCodes, true, 0);
    }

    public Product withDetails(String sku, String name, Kind kind, String unitCode, BigDecimal unitPrice,
                               List<String> taxRateCodes) {
        return new Product(id, tenantId, sku, name, kind, unitCode, unitPrice, taxRateCodes, active, version + 1);
    }

    public Product deactivated() {
        return new Product(id, tenantId, sku, name, kind, unitCode, unitPrice, taxRateCodes, false, version + 1);
    }
}
