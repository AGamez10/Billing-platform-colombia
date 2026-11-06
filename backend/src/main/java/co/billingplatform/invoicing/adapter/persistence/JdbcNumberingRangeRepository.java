package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.application.NumberingRangeRepository;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcNumberingRangeRepository implements NumberingRangeRepository {

    private final JdbcClient jdbc;

    JdbcNumberingRangeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Si hay varias vigentes, usa la que vence primero (agota resoluciones viejas antes). */
    @Override
    public Optional<NumberingRange> lockActiveInvoiceRange(TenantId tenantId, ProviderEnvironment environment, LocalDate onDate) {
        return jdbc.sql("""
                        SELECT * FROM numbering_ranges
                        WHERE tenant_id = :t AND document_kind = 'INVOICE' AND environment = :env AND active
                          AND :d BETWEEN valid_from AND valid_to AND next_number <= range_to
                        ORDER BY valid_to, id
                        LIMIT 1
                        FOR UPDATE
                        """)
                .param("t", tenantId.value())
                .param("env", environment.name())
                .param("d", Date.valueOf(onDate))
                .query(JdbcNumberingRangeRepository::map)
                .optional();
    }

    @Override
    public void save(NumberingRange range) {
        jdbc.sql("UPDATE numbering_ranges SET next_number = :n WHERE tenant_id = :t AND id = :id")
                .param("n", range.nextNumber())
                .param("t", range.tenantId().value())
                .param("id", range.id())
                .update();
    }

    @Override
    public void add(NumberingRange r) {
        jdbc.sql("""
                        INSERT INTO numbering_ranges (tenant_id, id, document_kind, environment, resolution_number, prefix,
                                                      range_from, range_to, valid_from, valid_to, next_number)
                        VALUES (:t, :id, 'INVOICE', :env, :res, :prefix, :from, :to, :vf, :vt, :next)
                        """)
                .param("t", r.tenantId().value())
                .param("id", r.id())
                .param("env", r.environment().name())
                .param("res", r.resolutionNumber())
                .param("prefix", r.prefix())
                .param("from", r.rangeFrom())
                .param("to", r.rangeTo())
                .param("vf", Date.valueOf(r.validFrom()))
                .param("vt", Date.valueOf(r.validTo()))
                .param("next", r.nextNumber())
                .update();
    }

    @Override
    public List<NumberingRange> list(TenantId tenantId) {
        return jdbc.sql("SELECT * FROM numbering_ranges WHERE tenant_id = :t AND active ORDER BY environment, valid_to DESC, id")
                .param("t", tenantId.value())
                .query(JdbcNumberingRangeRepository::map)
                .list();
    }

    private static NumberingRange map(ResultSet rs, int n) throws SQLException {
        return new NumberingRange(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                ProviderEnvironment.valueOf(rs.getString("environment")),
                rs.getString("resolution_number"),
                rs.getString("prefix"),
                rs.getLong("range_from"),
                rs.getLong("range_to"),
                rs.getDate("valid_from").toLocalDate(),
                rs.getDate("valid_to").toLocalDate(),
                rs.getLong("next_number"));
    }
}
