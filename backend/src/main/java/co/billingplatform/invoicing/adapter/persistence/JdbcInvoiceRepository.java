package co.billingplatform.invoicing.adapter.persistence;

import co.billingplatform.electronicinvoicing.domain.ElectronicDocument.NumberingAuthorization;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.invoicing.application.InvoiceRepository;
import co.billingplatform.invoicing.domain.DocumentNumber;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.platform.persistence.JsonText;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.Discount;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TaxCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistencia del agregado factura. Las lecturas para modificar bloquean la fila
 * ({@code FOR UPDATE}): cada caso de uso es "leer, cambiar, guardar" en una transacción, así
 * que dos peticiones concurrentes sobre la misma factura se serializan.
 */
@Repository
public class JdbcInvoiceRepository implements InvoiceRepository {

    private final JdbcClient jdbc;
    private final TaxCatalog taxes;
    private final DocumentCalculator calculator;

    public JdbcInvoiceRepository(JdbcClient jdbc, TaxCatalog taxes, DocumentCalculator calculator) {
        this.jdbc = jdbc;
        this.taxes = taxes;
        this.calculator = calculator;
    }

    // ------------------------------------------------------------------ lectura

    @Override
    public Optional<Invoice> findById(TenantId tenantId, UUID invoiceId) {
        return loadWhere("i.tenant_id = :t AND i.id = :id", Map.of("t", tenantId.value(), "id", invoiceId), true);
    }

    @Override
    public Optional<Invoice> findByExternalReference(TenantId tenantId, ExternalReference reference) {
        return loadWhere("i.tenant_id = :t AND i.external_reference = :ref",
                Map.of("t", tenantId.value(), "ref", reference.value()), true);
    }

    /** Lectura para consultar (API): sin bloqueo, para no esperar a un envío en curso. */
    public Optional<Invoice> findForRead(TenantId tenantId, UUID invoiceId) {
        return loadWhere("i.tenant_id = :t AND i.id = :id", Map.of("t", tenantId.value(), "id", invoiceId), false);
    }

    private Optional<Invoice> loadWhere(String where, Map<String, Object> params, boolean lock) {
        Optional<Map<String, Object>> row = jdbc.sql("""
                        SELECT i.*, r.resolution_number, r.valid_from AS r_valid_from, r.valid_to AS r_valid_to,
                               r.range_from, r.range_to,
                               i.customer_snapshot->>'idType' AS c_id_type, i.customer_snapshot->>'idNumber' AS c_id_number,
                               i.customer_snapshot->>'checkDigit' AS c_dv, i.customer_snapshot->>'name' AS c_name,
                               i.customer_snapshot->>'email' AS c_email,
                               ARRAY(SELECT jsonb_array_elements_text(i.rejection_reasons)) AS reasons
                        FROM invoices i
                        LEFT JOIN numbering_ranges r ON r.tenant_id = i.tenant_id AND r.id = i.numbering_range_id
                        """ + " WHERE " + where + (lock ? " FOR UPDATE OF i" : ""))
                .params(params)
                .query((rs, n) -> rowToMap(rs))
                .optional();
        return row.map(this::restore);
    }

    private Invoice restore(Map<String, Object> r) {
        UUID id = (UUID) r.get("id");
        TenantId tenant = new TenantId((UUID) r.get("tenant_id"));
        List<InvoiceLine> lines = jdbc.sql("""
                        SELECT * FROM invoice_lines WHERE tenant_id = :t AND invoice_id = :id ORDER BY line_number
                        """)
                .param("t", tenant.value()).param("id", id)
                .query((rs, n) -> new InvoiceLine(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("description"),
                        rs.getString("unit_code"),
                        rs.getBigDecimal("quantity").stripTrailingZeros(),
                        rs.getBigDecimal("unit_price").stripTrailingZeros(),
                        discount(rs.getString("discount_kind"), rs.getBigDecimal("discount_value")),
                        Arrays.asList((String[]) rs.getArray("tax_rate_codes").getArray())))
                .list();
        List<Invoice.StatusChange> history = jdbc.sql("""
                        SELECT from_status, to_status, occurred_at, detail FROM invoice_status_history
                        WHERE tenant_id = :t AND invoice_id = :id ORDER BY seq
                        """)
                .param("t", tenant.value()).param("id", id)
                .query((rs, n) -> new Invoice.StatusChange(
                        rs.getString("from_status") == null ? null : InvoiceStatus.valueOf(rs.getString("from_status")),
                        InvoiceStatus.valueOf(rs.getString("to_status")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("detail")))
                .list();

        String dv = (String) r.get("c_dv");
        Party customer = new Party(new PartyIdentification(IdentificationType.valueOf((String) r.get("c_id_type")),
                (String) r.get("c_id_number"), dv == null ? null : Integer.valueOf(dv)),
                (String) r.get("c_name"), (String) r.get("c_email"));

        Long number = (Long) r.get("number");
        String env = (String) r.get("environment");
        String validationEnv = (String) r.get("validation_environment");
        String ref = (String) r.get("external_reference");
        String[] reasons = (String[]) r.get("reasons");

        return Invoice.restore(new Invoice.Snapshot(
                id, tenant, customer, lines, ((Date) r.get("issue_date")).toLocalDate(),
                InvoiceStatus.valueOf((String) r.get("status")),
                number == null ? null : new DocumentNumber((String) r.get("prefix"), number),
                r.get("resolution_number") == null ? null : new NumberingAuthorization(
                        (String) r.get("resolution_number"), ((Date) r.get("r_valid_from")).toLocalDate(),
                        ((Date) r.get("r_valid_to")).toLocalDate(), (Long) r.get("range_from"), (Long) r.get("range_to")),
                (UUID) r.get("numbering_range_id"),
                (UUID) r.get("provider_account_id"),
                env == null ? null : ProviderEnvironment.valueOf(env),
                ref == null ? null : new ExternalReference(ref),
                validationEnv == null ? null : new ValidationEvidence((String) r.get("document_key"),
                        ProviderEnvironment.valueOf(validationEnv), ((Timestamp) r.get("validated_at")).toInstant()),
                reasons == null ? List.of() : Arrays.asList(reasons),
                (Boolean) r.get("outcome_uncertain"),
                (Integer) r.get("provider_interactions"),
                (String) r.get("last_error"),
                history,
                (BigDecimal) r.get("payable_amount")), taxes, calculator);
    }

    private static Discount discount(String kind, BigDecimal value) {
        return switch (kind) {
            case "PERCENT" -> new Discount.Percentage(value.stripTrailingZeros());
            case "AMOUNT" -> new Discount.Amount(value.stripTrailingZeros());
            default -> Discount.NONE;
        };
    }

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Array a) {
                v = a.getArray();
            }
            m.put(meta.getColumnLabel(i), v);
        }
        return m;
    }

    // ------------------------------------------------------------------ escritura

    @Override
    public void save(Invoice invoice) {
        UUID id = invoice.id();
        UUID tenant = invoice.tenantId().value();
        String storedStatus = jdbc.sql("SELECT status FROM invoices WHERE tenant_id = :t AND id = :id")
                .param("t", tenant).param("id", id).query(String.class).optional().orElse(null);

        if (storedStatus == null) {
            insertHeader(invoice);
            writeLines(invoice);
        } else {
            if ("DRAFT".equals(storedStatus)) {
                // Las líneas solo cambian mientras la factura es borrador: se reescriben ANTES de
                // actualizar el encabezado (el trigger de la base lo exige).
                jdbc.sql("DELETE FROM invoice_lines WHERE tenant_id = :t AND invoice_id = :id")
                        .param("t", tenant).param("id", id).update();
                writeLines(invoice);
            }
            updateHeader(invoice);
        }
        appendHistory(invoice);
    }

    @Override
    public void deleteDraft(TenantId tenantId, UUID invoiceId) {
        // Líneas e historial se borran en cascada (solo posible para borradores: trigger de la base).
        jdbc.sql("DELETE FROM invoices WHERE tenant_id = :t AND id = :id AND status = 'DRAFT'")
                .param("t", tenantId.value()).param("id", invoiceId).update();
    }

    private void insertHeader(Invoice inv) {
        jdbc.sql("""
                        INSERT INTO invoices (tenant_id, id, status, customer_snapshot, currency, issue_date,
                                              numbering_range_id, prefix, number, line_extension_amount, tax_total,
                                              payable_amount, provider_account_id, environment)
                        VALUES (:t, :id, :status, CAST(:customer AS jsonb), :currency, :issueDate,
                                NULL, :prefix, :number, :lineExt, :taxTotal, :payable, :account, :env)
                        """)
                .params(headerParams(inv))
                .update();
    }

    private void updateHeader(Invoice inv) {
        Map<String, Object> p = headerParams(inv);
        jdbc.sql("""
                        UPDATE invoices SET status = :status, customer_snapshot = CAST(:customer AS jsonb),
                               issue_date = :issueDate, prefix = :prefix, number = :number, numbering_range_id = :range,
                               line_extension_amount = :lineExt, tax_total = :taxTotal, payable_amount = :payable,
                               provider_account_id = :account, environment = :env,
                               external_reference = :ref, document_key = :docKey, validation_environment = :valEnv,
                               validated_at = :validatedAt, outcome_uncertain = :uncertain,
                               provider_interactions = :interactions, last_error = :lastError,
                               rejection_reasons = CAST(:reasons AS jsonb), version = version + 1, updated_at = now()
                        WHERE tenant_id = :t AND id = :id
                        """)
                .params(p)
                .update();
    }

    private Map<String, Object> headerParams(Invoice inv) {
        DocumentCalculator.Result calc = inv.calculation();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("idType", inv.customer().identification().type().name());
        customer.put("idNumber", inv.customer().identification().number());
        customer.put("checkDigit", inv.customer().identification().checkDigit());
        customer.put("name", inv.customer().legalName());
        customer.put("email", inv.customer().email());

        Map<String, Object> p = new HashMap<>();
        p.put("t", inv.tenantId().value());
        p.put("id", inv.id());
        p.put("status", inv.status().name());
        p.put("customer", JsonText.object(customer));
        p.put("currency", inv.currency());
        p.put("issueDate", Date.valueOf(inv.issueDate()));
        p.put("prefix", inv.number().map(DocumentNumber::prefix).orElse(null));
        p.put("number", inv.number().map(DocumentNumber::number).orElse(null));
        p.put("lineExt", calc.lineExtensionAmount());
        p.put("taxTotal", calc.taxTotal());
        p.put("payable", calc.payableAmount());
        p.put("range", inv.numberingRangeId().orElse(null));
        p.put("account", inv.providerAccountId().orElse(null));
        p.put("env", inv.environment().map(Enum::name).orElse(null));
        p.put("ref", inv.externalReference().map(ExternalReference::value).orElse(null));
        p.put("docKey", inv.evidence().map(ValidationEvidence::documentKey).orElse(null));
        p.put("valEnv", inv.evidence().map(e -> e.environment().name()).orElse(null));
        p.put("validatedAt", inv.evidence().map(e -> Timestamp.from(e.validatedAt())).orElse(null));
        p.put("uncertain", inv.outcomeUncertain());
        p.put("interactions", inv.providerInteractions());
        p.put("lastError", inv.lastError().map(s -> s.length() > 1000 ? s.substring(0, 1000) : s).orElse(null));
        p.put("reasons", JsonText.stringArray(inv.rejectionReasons()));
        return p;
    }

    private void writeLines(Invoice inv) {
        DocumentCalculator.Result calc = inv.calculation();
        for (int i = 0; i < inv.lines().size(); i++) {
            InvoiceLine line = inv.lines().get(i);
            DocumentCalculator.CalculatedLine c = calc.lines().get(i);
            List<String> taxes = new ArrayList<>();
            for (DocumentCalculator.LineTax t : c.taxes()) {
                Map<String, Object> tax = new LinkedHashMap<>();
                tax.put("code", t.rate().code());
                tax.put("type", t.rate().type().name());
                tax.put("treatment", t.rate().treatment().name());
                tax.put("percentage", t.rate().percentage().toPlainString());
                tax.put("taxable", t.taxableAmount().toPlainString());
                tax.put("amount", t.taxAmount().toPlainString());
                taxes.add(JsonText.object(tax));
            }
            String kind;
            BigDecimal discountValue;
            switch (line.discount()) {
                case Discount.Percentage pct -> { kind = "PERCENT"; discountValue = pct.percent(); }
                case Discount.Amount amount -> {
                    kind = amount.value().signum() == 0 ? "NONE" : "AMOUNT";
                    discountValue = amount.value();
                }
            }
            jdbc.sql("""
                            INSERT INTO invoice_lines (tenant_id, invoice_id, line_number, product_id, description, unit_code,
                                                       quantity, unit_price, gross_amount, discount_amount, taxable_amount,
                                                       tax_total, line_total, taxes, discount_kind, discount_value, tax_rate_codes)
                            VALUES (:t, :inv, :n, :product, :description, :unit, :qty, :price, :gross, :discount, :taxable,
                                    :taxTotal, :lineTotal, CAST(:taxes AS jsonb), :kind, :dvalue,
                                    ARRAY(SELECT jsonb_array_elements_text(CAST(:codes AS jsonb))))
                            """)
                    .param("t", inv.tenantId().value())
                    .param("inv", inv.id())
                    .param("n", i + 1)
                    .param("product", line.productId())
                    .param("description", line.description())
                    .param("unit", line.unitCode())
                    .param("qty", line.quantity())
                    .param("price", line.unitPrice())
                    .param("gross", c.grossAmount())
                    .param("discount", c.discountAmount())
                    .param("taxable", c.taxableAmount())
                    .param("taxTotal", c.taxTotal())
                    .param("lineTotal", c.lineTotal())
                    .param("taxes", "[" + String.join(",", taxes) + "]")
                    .param("kind", kind)
                    .param("dvalue", discountValue)
                    // Un String[] lo expandiría NamedParameterJdbcTemplate como lista "?, ?": va como JSON.
                    .param("codes", JsonText.stringArray(line.taxRateCodes()))
                    .update();
        }
    }

    private void appendHistory(Invoice inv) {
        int stored = jdbc.sql("SELECT count(*) FROM invoice_status_history WHERE tenant_id = :t AND invoice_id = :id")
                .param("t", inv.tenantId().value()).param("id", inv.id()).query(Integer.class).single();
        List<Invoice.StatusChange> history = inv.history();
        for (int i = stored; i < history.size(); i++) {
            Invoice.StatusChange h = history.get(i);
            jdbc.sql("""
                            INSERT INTO invoice_status_history (tenant_id, invoice_id, seq, from_status, to_status, occurred_at, detail)
                            VALUES (:t, :id, :seq, :from, :to, :at, :detail)
                            """)
                    .param("t", inv.tenantId().value())
                    .param("id", inv.id())
                    .param("seq", i + 1)
                    .param("from", h.from() == null ? null : h.from().name())
                    .param("to", h.to().name())
                    .param("at", Timestamp.from(h.at()))
                    .param("detail", h.detail() == null ? null : (h.detail().length() > 1000 ? h.detail().substring(0, 1000) : h.detail()))
                    .update();
        }
    }
}
