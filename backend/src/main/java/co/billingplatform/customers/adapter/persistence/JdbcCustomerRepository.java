package co.billingplatform.customers.adapter.persistence;

import co.billingplatform.customers.application.CustomerRepository;
import co.billingplatform.customers.domain.Customer;
import co.billingplatform.platform.persistence.SqlText;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Toda consulta filtra por tenant además de correr bajo RLS (dos barreras). */
@Repository
class JdbcCustomerRepository implements CustomerRepository {

    private final JdbcClient jdbc;

    JdbcCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Customer c) {
        try {
            jdbc.sql("""
                            INSERT INTO customers (tenant_id, id, id_type, id_number, check_digit, legal_name, email,
                                                   phone, address, city_code, active, version)
                            VALUES (:t, :id, :type, :number, :dv, :name, :email, :phone, :address, :city, :active, :v)
                            """)
                    .params(params(c))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DomainException("CUSTOMER_ALREADY_EXISTS", "A customer with that identification already exists");
        }
    }

    @Override
    public boolean update(Customer c) {
        try {
            return jdbc.sql("""
                            UPDATE customers SET id_type = :type, id_number = :number, check_digit = :dv,
                                   legal_name = :name, email = :email, phone = :phone, address = :address,
                                   city_code = :city, active = :active, version = :v, updated_at = now()
                            WHERE tenant_id = :t AND id = :id AND version = :v - 1
                            """)
                    .params(params(c))
                    .update() == 1;
        } catch (DuplicateKeyException e) {
            throw new DomainException("CUSTOMER_ALREADY_EXISTS", "A customer with that identification already exists");
        }
    }

    @Override
    public Optional<Customer> findById(TenantId tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM customers WHERE tenant_id = :t AND id = :id")
                .param("t", tenantId.value())
                .param("id", id)
                .query(JdbcCustomerRepository::map)
                .optional();
    }

    @Override
    public Page<Customer> search(TenantId tenantId, String query, boolean includeInactive, int limit, int offset) {
        List<Customer> rows = jdbc.sql("""
                        SELECT * FROM customers
                        WHERE tenant_id = :t
                          AND (:all OR active)
                          AND (:q = '' OR lower(legal_name) LIKE :pattern ESCAPE '\\' OR id_number LIKE :prefix ESCAPE '\\')
                        ORDER BY lower(legal_name), id
                        LIMIT :lim OFFSET :off
                        """)
                .param("t", tenantId.value())
                .param("all", includeInactive)
                .param("q", query)
                .param("pattern", "%" + SqlText.escapeLike(query.toLowerCase(java.util.Locale.ROOT)) + "%")
                .param("prefix", SqlText.escapeLike(query) + "%")
                .param("lim", limit + 1)
                .param("off", offset)
                .query(JdbcCustomerRepository::map)
                .list();
        return Page.of(rows, limit, offset);
    }

    private static java.util.Map<String, Object> params(Customer c) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("t", c.tenantId().value());
        p.put("id", c.id());
        p.put("type", c.identification().type().name());
        p.put("number", c.identification().number());
        p.put("dv", c.identification().checkDigit() == null ? null : c.identification().checkDigit().shortValue());
        p.put("name", c.legalName());
        p.put("email", c.email());
        p.put("phone", c.phone());
        p.put("address", c.address());
        p.put("city", c.cityCode());
        p.put("active", c.active());
        p.put("v", c.version());
        return p;
    }

    private static Customer map(ResultSet rs, int row) throws SQLException {
        Object dv = rs.getObject("check_digit");
        return new Customer(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new PartyIdentification(IdentificationType.valueOf(rs.getString("id_type")), rs.getString("id_number"),
                        dv == null ? null : ((Number) dv).intValue()),
                rs.getString("legal_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("address"),
                rs.getString("city_code"),
                rs.getBoolean("active"),
                rs.getInt("version"));
    }
}
