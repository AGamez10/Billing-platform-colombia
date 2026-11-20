package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.application.CreditNoteService;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcCreditedQuantities implements CreditNoteService.CreditedQuantities {

    private final JdbcClient jdbc;

    JdbcCreditedQuantities(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Integer, BigDecimal> creditedByLine(TenantId tenantId, UUID invoiceId) {
        Map<Integer, BigDecimal> out = new HashMap<>();
        jdbc.sql("""
                        SELECT l.credited_line_number AS line, sum(l.quantity) AS qty
                        FROM invoices n
                        JOIN invoice_lines l ON l.tenant_id = n.tenant_id AND l.invoice_id = n.id
                        WHERE n.tenant_id = :t AND n.credited_invoice_id = :id AND n.status NOT IN ('REJECTED', 'FAILED')
                        GROUP BY l.credited_line_number
                        """)
                .param("t", tenantId.value())
                .param("id", invoiceId)
                .query(rs -> {
                    out.put(rs.getInt("line"), rs.getBigDecimal("qty"));
                });
        return out;
    }
}
