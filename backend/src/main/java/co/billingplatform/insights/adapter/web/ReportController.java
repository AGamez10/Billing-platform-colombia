package co.billingplatform.insights.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.insights.adapter.persistence.JdbcSalesReport;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.shared.csv.Csv;
import co.billingplatform.shared.domain.DomainException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reporte de ventas e impuestos por periodo, en pantalla y en CSV para el contador. */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reportes")
@SecurityRequirement(name = "bearer")
class ReportController {

    record Summary(String environment, LocalDate from, LocalDate to, int documents, int creditNotes, BigDecimal subtotal,
                   BigDecimal taxTotal, BigDecimal total, List<JdbcSalesReport.TaxLine> taxes) {}

    private static final Map<String, String> KIND = Map.of("INVOICE", "Factura", "POS_EQUIVALENT", "POS", "CREDIT_NOTE", "Nota crédito");

    private final JdbcSalesReport report;
    private final TenantTransactions tx;

    ReportController(JdbcSalesReport report, TenantTransactions tx) {
        this.report = report;
        this.tx = tx;
    }

    @GetMapping("/sales")
    @Operation(summary = "Resumen de ventas validadas en el periodo (máx. 366 días): subtotal, impuestos por tipo y tarifa, total. "
            + "Ambiente: el activo, o el indicado.")
    Summary summary(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    @RequestParam(required = false) String environment) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        validate(from, to);
        return tx.inTenant(u.tenantId(), () -> {
            String env = environment(u, environment);
            var docs = report.documents(u.tenantId(), env, from, to);
            BigDecimal subtotal = docs.stream().map(JdbcSalesReport.Document::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal taxes = docs.stream().map(JdbcSalesReport.Document::taxTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = docs.stream().map(JdbcSalesReport.Document::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            int notes = (int) docs.stream().filter(d -> d.kind().equals("CREDIT_NOTE")).count();
            return new Summary(env, from, to, docs.size() - notes, notes, subtotal, taxes, total,
                    report.taxes(u.tenantId(), env, from, to));
        });
    }

    @GetMapping(value = "/sales.csv", produces = "text/csv")
    @Operation(summary = "Documentos validados del periodo en CSV (punto y coma, decimales con coma, UTF-8): para Excel o el contador")
    ResponseEntity<byte[]> csv(@AuthenticationPrincipal Jwt jwt, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) String environment) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        validate(from, to);
        String body = tx.inTenant(u.tenantId(), () -> {
            String env = environment(u, environment);
            List<List<String>> rows = new ArrayList<>();
            for (var d : report.documents(u.tenantId(), env, from, to)) {
                rows.add(List.of(d.issueDate().toString(), KIND.getOrDefault(d.kind(), d.kind()), d.number(),
                        d.customerIdType() == null ? "" : d.customerIdType(), d.customerId() == null ? "" : d.customerId(),
                        d.customerName() == null ? "" : d.customerName(), money(d.subtotal()), money(d.taxTotal()), money(d.total()),
                        d.creditedNumber() == null ? "" : d.creditedNumber(), d.documentKey() == null ? "" : d.documentKey(), env));
            }
            return Csv.write(List.of("Fecha", "Tipo", "Número", "Tipo de documento", "Identificación", "Cliente", "Subtotal",
                    "Impuestos", "Total", "Corrige a", "CUFE/CUDE", "Ambiente"), rows);
        });
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("ventas-" + from + "-a-" + to + ".csv").build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    private String environment(CurrentUser u, String requested) {
        if (requested != null && !requested.isBlank()) {
            if (!List.of("SIMULATION", "HABILITATION", "PRODUCTION").contains(requested)) {
                throw new DomainException("INVALID_ENVIRONMENT", "Unknown environment");
            }
            return requested;
        }
        return report.activeEnvironment(u.tenantId()).orElse("PRODUCTION");
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new DomainException("INVALID_PERIOD", "The period must be at most one year and end after it starts");
        }
    }

    /** Decimales con coma, como los lee Excel en español. */
    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }
}
