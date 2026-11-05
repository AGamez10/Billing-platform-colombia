package co.billingplatform.products.adapter.persistence;

import co.billingplatform.platform.persistence.SqlText;
import co.billingplatform.products.application.ProductRepository;
import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProductRepository implements ProductRepository {

    private final JdbcClient jdbc;

    JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Product p) {
        try {
            jdbc.sql("""
                            INSERT INTO products (tenant_id, id, sku, name, kind, unit_code, unit_price, tax_rate_codes,
                                                  active, version)
                            VALUES (:t, :id, :sku, :name, :kind, :unit, :price, :taxes, :active, :v)
                            """)
                    .params(params(p))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DomainException("PRODUCT_SKU_ALREADY_EXISTS", "A product with that SKU already exists");
        }
    }

    @Override
    public boolean update(Product p) {
        try {
            return jdbc.sql("""
                            UPDATE products SET sku = :sku, name = :name, kind = :kind, unit_code = :unit,
                                   unit_price = :price, tax_rate_codes = :taxes, active = :active, version = :v,
                                   updated_at = now()
                            WHERE tenant_id = :t AND id = :id AND version = :v - 1
                            """)
                    .params(params(p))
                    .update() == 1;
        } catch (DuplicateKeyException e) {
            throw new DomainException("PRODUCT_SKU_ALREADY_EXISTS", "A product with that SKU already exists");
        }
    }

    @Override
    public Optional<Product> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM products WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId.value())
                .param("id", id)
                .query(JdbcProductRepository::map)
                .optional();
    }

    @Override
    public Page<Product> search(TenantId tenantId, String query, boolean includeInactive, int limit, int offset) {
        List<Product> rows = jdbc.sql("""
                        SELECT * FROM products
                        WHERE tenant_id = :t
                          AND (:all OR active)
                          AND (:q = '' OR lower(name) LIKE :pattern ESCAPE '\\' OR lower(sku) LIKE :pattern ESCAPE '\\')
                        ORDER BY lower(name), id
                        LIMIT :lim OFFSET :off
                        """)
                .param("t", tenantId.value())
                .param("all", includeInactive)
                .param("q", query)
                .param("pattern", "%" + SqlText.escapeLike(query.toLowerCase(Locale.ROOT)) + "%")
                .param("lim", limit + 1)
                .param("off", offset)
                .query(JdbcProductRepository::map)
                .list();
        return Page.of(rows, limit, offset);
    }

    private static Map<String, Object> params(Product p) {
        Map<String, Object> m = new HashMap<>();
        m.put("t", p.tenantId().value());
        m.put("id", p.id());
        m.put("sku", p.sku());
        m.put("name", p.name());
        m.put("kind", p.kind().name());
        m.put("unit", p.unitCode());
        m.put("price", p.unitPrice());
        m.put("taxes", p.taxRateCodes().toArray(String[]::new));
        m.put("active", p.active());
        m.put("v", p.version());
        return m;
    }

    private static Product map(ResultSet rs, int row) throws SQLException {
        String[] taxes = (String[]) rs.getArray("tax_rate_codes").getArray();
        return new Product(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                rs.getString("sku"),
                rs.getString("name"),
                Product.Kind.valueOf(rs.getString("kind")),
                rs.getString("unit_code"),
                rs.getBigDecimal("unit_price"),
                Arrays.asList(taxes),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }
}
