package co.billingplatform.platform.web;

import co.billingplatform.customers.application.CustomerImportService;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.products.application.ProductImportService;
import co.billingplatform.shared.application.ImportResult;
import co.billingplatform.shared.csv.Csv;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Importar clientes y productos desde CSV. El archivo va como texto en el cuerpo (máx. 1 MB,
 * 2.000 filas). {@code commit=false} (por defecto) es una vista previa que no guarda nada.
 */
@RestController
@Tag(name = "Importación")
@SecurityRequirement(name = "bearer")
class ImportController {

    private final CustomerImportService customers;
    private final ProductImportService products;
    private final TenantTransactions tx;

    ImportController(CustomerImportService customers, ProductImportService products, TenantTransactions tx) {
        this.customers = customers;
        this.products = products;
        this.tx = tx;
    }

    @PostMapping(value = "/api/v1/customers/import", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    @Operation(summary = "Importar clientes (vista previa o confirmación)")
    ImportResult importCustomers(@AuthenticationPrincipal Jwt jwt, @RequestBody String csv,
                                 @RequestParam(defaultValue = "false") boolean commit) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_WRITE);
        return tx.inTenant(u.tenantId(), () -> customers.importCsv(u.tenantId(), u.actor(), csv, commit));
    }

    @PostMapping(value = "/api/v1/products/import", consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE})
    @Operation(summary = "Importar productos y servicios (vista previa o confirmación)")
    ImportResult importProducts(@AuthenticationPrincipal Jwt jwt, @RequestBody String csv,
                                @RequestParam(defaultValue = "false") boolean commit) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_WRITE);
        return tx.inTenant(u.tenantId(), () -> products.importCsv(u.tenantId(), u.actor(), csv, commit));
    }

    @GetMapping("/api/v1/public/import-templates/customers.csv")
    @Operation(summary = "Plantilla CSV de clientes")
    ResponseEntity<byte[]> customerTemplate() {
        return template("plantilla-clientes.csv", CustomerImportService.COLUMNS, List.of(
                List.of("NIT", "900123456", "", "Comercializadora Ejemplo S.A.S.", "compras@ejemplo.co", "6015550101", "Calle 10 # 5-20", "11001", "juridica", "48", "O-13|O-23"),
                List.of("CC", "1020304050", "", "Ana Pérez", "ana@correo.co", "3001234567", "", "", "", "", "")));
    }

    @GetMapping("/api/v1/public/import-templates/products.csv")
    @Operation(summary = "Plantilla CSV de productos")
    ResponseEntity<byte[]> productTemplate() {
        return template("plantilla-productos.csv", ProductImportService.COLUMNS, List.of(
                List.of("Concentrado para perro 2 kg", "ALI-002", "producto", "94", "45000", "IVA_19"),
                List.of("Consulta veterinaria", "SER-001", "servicio", "94", "60000", "IVA_EXCLUIDO")));
    }

    private static ResponseEntity<byte[]> template(String name, List<String> header, List<List<String>> rows) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(Csv.write(header, rows).getBytes(StandardCharsets.UTF_8));
    }
}
