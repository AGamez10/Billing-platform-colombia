package co.billingplatform.products.application;

import co.billingplatform.products.domain.Product;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.ImportResult;
import co.billingplatform.shared.csv.Csv;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Importar productos y servicios desde CSV. Columnas: {@code nombre, sku, tipo, unidad, precio,
 * impuestos}. Los impuestos son obligatorios (códigos del catálogo separados por {@code |}, por
 * ejemplo {@code IVA_19} o {@code IVA_EXCLUIDO}): nunca se suponen.
 */
public final class ProductImportService {

    public static final List<String> COLUMNS = List.of("nombre", "sku", "tipo", "unidad", "precio", "impuestos");

    private final ProductRepository products;
    private final ProductService service;
    private final AuditTrail audit;

    public ProductImportService(ProductRepository products, ProductService service, AuditTrail audit) {
        this.products = Objects.requireNonNull(products);
        this.service = Objects.requireNonNull(service);
        this.audit = Objects.requireNonNull(audit);
    }

    public ImportResult importCsv(TenantId tenantId, Actor actor, String csv, boolean commit) {
        List<Csv.Row> rows = Csv.read(csv);
        if (!rows.isEmpty() && !rows.getFirst().values().containsKey("nombre")) {
            throw new DomainException("IMPORT_MISSING_COLUMNS", "Required columns: nombre, precio, impuestos");
        }
        List<ImportResult.RowError> errors = new ArrayList<>();
        List<Product> valid = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        for (Csv.Row row : rows) {
            try {
                Product p = parse(tenantId, row);
                if (p.sku() != null && !skus.add(p.sku())) {
                    errors.add(new ImportResult.RowError(row.line(), "SKU repetido en el archivo"));
                    continue;
                }
                valid.add(p);
                lines.add(row.line());
            } catch (DomainException e) {
                errors.add(new ImportResult.RowError(row.line(), e.getMessage() + " (" + e.code() + ")"));
            }
        }
        Set<String> existing = products.existingSkus(tenantId, valid.stream().map(Product::sku).filter(Objects::nonNull).toList());
        List<Product> toCreate = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            Product p = valid.get(i);
            if (p.sku() != null && existing.contains(p.sku())) {
                errors.add(new ImportResult.RowError(lines.get(i), "Ya existe un producto con ese SKU"));
            } else {
                toCreate.add(p);
            }
        }
        errors.sort((a, b) -> Integer.compare(a.line(), b.line()));
        if (commit) {
            toCreate.forEach(products::insert);
            if (!toCreate.isEmpty()) {
                audit.record(new AuditTrail.Event(tenantId, actor, "PRODUCTS_IMPORTED", "product", "import",
                        Map.of("created", String.valueOf(toCreate.size()), "rejected", String.valueOf(errors.size()))));
            }
        }
        return new ImportResult(commit, rows.size(), toCreate.size(), commit ? toCreate.size() : 0, List.copyOf(errors));
    }

    private Product parse(TenantId tenantId, Csv.Row row) {
        String kindText = row.get("tipo");
        Product.Kind kind = kindText == null ? Product.Kind.PRODUCT : switch (kindText.toLowerCase(Locale.ROOT)) {
            case "producto", "product", "bien" -> Product.Kind.PRODUCT;
            case "servicio", "service" -> Product.Kind.SERVICE;
            default -> throw new DomainException("INVALID_PRODUCT", "tipo debe ser producto o servicio");
        };
        String taxes = row.get("impuestos");
        if (taxes == null) {
            throw new DomainException("INVALID_PRODUCT", "impuestos es obligatorio (p. ej. IVA_19 o IVA_EXCLUIDO)");
        }
        List<String> codes = Arrays.stream(taxes.split("[|,;]")).map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        service.validateTaxCodes(codes);
        return Product.create(tenantId, row.get("sku"), row.get("nombre"), kind,
                row.get("unidad") == null ? "94" : row.get("unidad"), price(row.get("precio")), codes);
    }

    /** "25.000", "25000", "25000,50", "25,000.50" → BigDecimal; sin símbolo de moneda. */
    static BigDecimal price(String text) {
        if (text == null) {
            throw new DomainException("INVALID_PRODUCT", "precio es obligatorio");
        }
        String t = text.replaceAll("[$\\s]", "");
        if (t.matches("[0-9]{1,3}(\\.[0-9]{3})+(,[0-9]{1,2})?")) {
            t = t.replace(".", "").replace(',', '.');
        } else if (t.matches("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]{1,2})?")) {
            t = t.replace(",", "");
        } else {
            t = t.replace(',', '.');
        }
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.signum() < 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new DomainException("INVALID_PRODUCT", "precio no es un número válido: " + text);
        }
    }
}
