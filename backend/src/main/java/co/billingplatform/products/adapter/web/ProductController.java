package co.billingplatform.products.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.products.application.ProductService;
import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Productos y servicios")
@SecurityRequirement(name = "bearer")
class ProductController {

    /** {@code unitPrice} como texto decimal (p. ej. "15000" o "12500.50"), nunca número flotante. */
    record ProductRequest(
            @Size(max = 50) String sku,
            @NotBlank @Size(max = 300) String name,
            @NotNull Product.Kind kind,
            @NotBlank @Size(max = 10) String unitCode,
            @NotBlank @Size(max = 25) String unitPrice,
            @Size(max = 5) List<@NotBlank String> taxRateCodes) {
        ProductService.Details details() {
            BigDecimal price;
            try {
                price = new BigDecimal(unitPrice.strip());
            } catch (NumberFormatException e) {
                throw new DomainException("INVALID_PRODUCT", "Unit price must be a decimal number like 15000 or 12500.50");
            }
            return new ProductService.Details(sku, name, kind, unitCode, price, taxRateCodes);
        }
    }

    record UpdateProductRequest(@NotNull @PositiveOrZero Integer version, @NotNull @Valid ProductRequest product) {}

    record ProductView(UUID id, String sku, String name, String kind, String unitCode, String unitPrice,
                       List<String> taxRateCodes, boolean active, int version) {
        static ProductView of(Product p) {
            return new ProductView(p.id(), p.sku(), p.name(), p.kind().name(), p.unitCode(),
                    p.unitPrice().toPlainString(), p.taxRateCodes(), p.active(), p.version());
        }
    }

    private final ProductService products;
    private final TenantTransactions tx;

    ProductController(ProductService products, TenantTransactions tx) {
        this.products = products;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear producto o servicio (códigos de tarifa: ver /api/v1/public/tax-rates)")
    ProductView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_WRITE);
        return tx.inTenant(u.tenantId(), () -> ProductView.of(products.create(u.tenantId(), u.actor(), r.details())));
    }

    @GetMapping
    @Operation(summary = "Buscar productos por nombre o SKU (paginado, máx. 100)")
    Page<ProductView> search(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "false") boolean includeInactive,
                             @RequestParam(required = false) Integer limit,
                             @RequestParam(required = false) Integer offset) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_READ);
        return tx.inTenant(u.tenantId(), () -> {
            Page<Product> page = products.search(u.tenantId(), q, includeInactive, limit, offset);
            return new Page<>(page.items().stream().map(ProductView::of).toList(), page.limit(), page.offset(), page.hasMore());
        });
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar producto")
    ProductView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_READ);
        return tx.inTenant(u.tenantId(), () -> ProductView.of(products.get(u.tenantId(), id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar producto (envía la versión que leíste; si otro lo cambió responde 409)")
    ProductView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_WRITE);
        return tx.inTenant(u.tenantId(), () ->
                ProductView.of(products.update(u.tenantId(), u.actor(), id, r.version(), r.product().details())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar producto")
    ProductView deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PRODUCT_WRITE);
        return tx.inTenant(u.tenantId(), () -> ProductView.of(products.deactivate(u.tenantId(), u.actor(), id)));
    }
}
