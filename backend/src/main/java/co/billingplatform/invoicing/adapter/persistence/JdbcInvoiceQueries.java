package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.domain.Page;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Consultas de solo lectura para listados (sin reconstruir el agregado ni bloquear filas). */
@Component
public class JdbcInvoiceQueries {

    public record InvoiceSummary(UUID id, String status, String number, LocalDate issueDate, String environment,
                                 String customerName, String customerIdNumber, BigDecimal payableAmount,
                                 boolean legallyValid) {}

    private final JdbcClient jdbc;

    JdbcInvoiceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<InvoiceSummary> list(TenantId tenantId, InvoiceStatus status, Integer limit, Integer offset) {
        int l = Page.clampLimit(limit);
        int o = Page.clampOffset(offset);
        String statusFilter = status == null ? "" : " AND status = :status";
        var spec = jdbc.sql("""
                        SELECT id, status, prefix, number, issue_date, environment, payable_amount, validation_environment,
                               customer_snapshot->>'name' AS customer_name, customer_snapshot->>'idNumber' AS customer_id_number
                        FROM invoices
                        WHERE tenant_id = :t""" + statusFilter + """

                        ORDER BY issue_date DESC, created_at DESC, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("t", tenantId.value())
                .param("limit", l + 1)
                .param("offset", o);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        var rows = spec.query((rs, n) -> {
            long number = rs.getLong("number");
            boolean numbered = !rs.wasNull();
            String st = rs.getString("status");
            return new InvoiceSummary(
                    rs.getObject("id", UUID.class), st,
                    numbered ? rs.getString("prefix") + number : null,
                    rs.getDate("issue_date").toLocalDate(),
                    rs.getString("environment"),
                    rs.getString("customer_name"),
                    rs.getString("customer_id_number"),
                    rs.getBigDecimal("payable_amount"),
                    "ACCEPTED".equals(st) && "PRODUCTION".equals(rs.getString("validation_environment")));
        }).list();
        return Page.of(rows, l, o);
    }
}
