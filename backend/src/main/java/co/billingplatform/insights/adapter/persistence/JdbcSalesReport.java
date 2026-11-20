package co.billingplatform.insights.adapter.persistence;

import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reporte de ventas para el contador: documentos aceptados en un ambiente y periodo, con sus
 * impuestos por tipo y tarifa. Las notas crédito restan (valores negativos).
 */
@Repository
public class JdbcSalesReport {

    public record Document(UUID id, LocalDate issueDate, String kind, String number, String customerIdType, String customerId,
                           String customerName, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total, String documentKey,
                           String creditedNumber) {}

    public record TaxLine(String type, String percentage, BigDecimal taxable, BigDecimal amount) {}

    private final JdbcClient jdbc;

    JdbcSalesReport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> activeEnvironment(TenantId tenantId) {
        return jdbc.sql("SELECT environment FROM provider_accounts WHERE tenant_id = :t AND active")
                .param("t", tenantId.value()).query(String.class).optional();
    }

    public List<Document> documents(TenantId tenantId, String environment, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT id, issue_date, document_kind, prefix || number AS number,
                               customer_snapshot->>'idType' AS id_type, customer_snapshot->>'idNumber' AS id_number,
                               customer_snapshot->>'name' AS name,
                               CASE WHEN document_kind = 'CREDIT_NOTE' THEN -1 ELSE 1 END AS sign,
                               line_extension_amount, tax_total, payable_amount, document_key, credited_number
                        FROM invoices
                        WHERE tenant_id = :t AND status = 'ACCEPTED' AND validation_environment = :env
                          AND issue_date BETWEEN :from AND :to
                        ORDER BY issue_date, document_kind, prefix, number
                        """)
                .param("t", tenantId.value()).param("env", environment)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> {
                    BigDecimal sign = BigDecimal.valueOf(rs.getInt("sign"));
                    return new Document(rs.getObject("id", UUID.class), rs.getDate("issue_date").toLocalDate(),
                            rs.getString("document_kind"), rs.getString("number"), rs.getString("id_type"), rs.getString("id_number"),
                            rs.getString("name"), rs.getBigDecimal("line_extension_amount").multiply(sign),
                            rs.getBigDecimal("tax_total").multiply(sign), rs.getBigDecimal("payable_amount").multiply(sign),
                            rs.getString("document_key"), rs.getString("credited_number"));
                })
                .list();
    }

    public List<TaxLine> taxes(TenantId tenantId, String environment, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT t->>'type' AS type, t->>'percentage' AS pct,
                               sum(CAST(t->>'taxable' AS numeric) * CASE WHEN i.document_kind = 'CREDIT_NOTE' THEN -1 ELSE 1 END) AS taxable,
                               sum(CAST(t->>'amount' AS numeric) * CASE WHEN i.document_kind = 'CREDIT_NOTE' THEN -1 ELSE 1 END) AS amount
                        FROM invoices i
                        JOIN invoice_lines l ON l.tenant_id = i.tenant_id AND l.invoice_id = i.id
                        CROSS JOIN LATERAL jsonb_array_elements(l.taxes) t
                        WHERE i.tenant_id = :t AND i.status = 'ACCEPTED' AND i.validation_environment = :env
                          AND i.issue_date BETWEEN :from AND :to
                        GROUP BY 1, 2 ORDER BY 1, CAST(t->>'percentage' AS numeric) DESC
                        """)
                .param("t", tenantId.value()).param("env", environment)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new TaxLine(rs.getString("type"), rs.getString("pct"), rs.getBigDecimal("taxable"), rs.getBigDecimal("amount")))
                .list();
    }
}
