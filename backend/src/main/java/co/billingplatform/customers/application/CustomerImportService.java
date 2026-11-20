package co.billingplatform.customers.application;

import co.billingplatform.customers.domain.Customer;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.ImportResult;
import co.billingplatform.shared.application.Municipalities;
import co.billingplatform.shared.csv.Csv;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.FiscalProfile;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.NitCheckDigit;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Importar clientes desde CSV (lo que exportan otros sistemas o una hoja de cálculo). Cada fila
 * pasa por las mismas reglas que crear un cliente a mano; las inválidas o repetidas se informan
 * por línea y no se guardan. Columnas (encabezados sin importar tildes ni mayúsculas):
 * {@code tipo_documento, numero, dv, nombre, correo, telefono, direccion, municipio,
 * tipo_persona, regimen, responsabilidades}.
 */
public final class CustomerImportService {

    public static final List<String> COLUMNS = List.of("tipo_documento", "numero", "dv", "nombre", "correo", "telefono",
            "direccion", "municipio", "tipo_persona", "regimen", "responsabilidades");

    private static final Map<String, IdentificationType> TYPES = Map.ofEntries(
            Map.entry("NIT", IdentificationType.NIT), Map.entry("CC", IdentificationType.CEDULA_CIUDADANIA),
            Map.entry("CEDULA", IdentificationType.CEDULA_CIUDADANIA), Map.entry("CEDULA_CIUDADANIA", IdentificationType.CEDULA_CIUDADANIA),
            Map.entry("CE", IdentificationType.CEDULA_EXTRANJERIA), Map.entry("CEDULA_EXTRANJERIA", IdentificationType.CEDULA_EXTRANJERIA),
            Map.entry("PA", IdentificationType.PASAPORTE), Map.entry("PASAPORTE", IdentificationType.PASAPORTE),
            Map.entry("TI", IdentificationType.TARJETA_IDENTIDAD), Map.entry("TARJETA_IDENTIDAD", IdentificationType.TARJETA_IDENTIDAD),
            Map.entry("DE", IdentificationType.DOCUMENTO_EXTRANJERO), Map.entry("DOCUMENTO_EXTRANJERO", IdentificationType.DOCUMENTO_EXTRANJERO));

    private final CustomerRepository customers;
    private final Municipalities municipalities;
    private final AuditTrail audit;

    public CustomerImportService(CustomerRepository customers, Municipalities municipalities, AuditTrail audit) {
        this.customers = Objects.requireNonNull(customers);
        this.municipalities = Objects.requireNonNull(municipalities);
        this.audit = Objects.requireNonNull(audit);
    }

    public ImportResult importCsv(TenantId tenantId, Actor actor, String csv, boolean commit) {
        List<Csv.Row> rows = Csv.read(csv);
        if (!rows.isEmpty() && !rows.getFirst().values().containsKey("numero")) {
            throw new DomainException("IMPORT_MISSING_COLUMNS", "Required columns: tipo_documento, numero, nombre");
        }
        List<ImportResult.RowError> errors = new ArrayList<>();
        List<Customer> valid = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Csv.Row row : rows) {
            try {
                Customer c = parse(tenantId, row);
                String key = c.identification().type().name() + ":" + c.identification().number();
                if (!seen.add(key)) {
                    errors.add(new ImportResult.RowError(row.line(), "Identificación repetida en el archivo"));
                    continue;
                }
                valid.add(c);
                lines.add(row.line());
            } catch (DomainException e) {
                errors.add(new ImportResult.RowError(row.line(), message(e)));
            }
        }
        Set<String> existing = customers.existingIdentifications(tenantId,
                valid.stream().map(c -> c.identification().type().name() + ":" + c.identification().number()).toList());
        List<Customer> toCreate = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            Customer c = valid.get(i);
            if (existing.contains(c.identification().type().name() + ":" + c.identification().number())) {
                errors.add(new ImportResult.RowError(lines.get(i), "Ya existe un cliente con esa identificación"));
            } else {
                toCreate.add(c);
            }
        }
        errors.sort((a, b) -> Integer.compare(a.line(), b.line()));
        if (commit) {
            toCreate.forEach(customers::insert);
            if (!toCreate.isEmpty()) {
                audit.record(new AuditTrail.Event(tenantId, actor, "CUSTOMERS_IMPORTED", "customer", "import",
                        Map.of("created", String.valueOf(toCreate.size()), "rejected", String.valueOf(errors.size()))));
            }
        }
        return new ImportResult(commit, rows.size(), toCreate.size(), commit ? toCreate.size() : 0, List.copyOf(errors));
    }

    private Customer parse(TenantId tenantId, Csv.Row row) {
        String typeText = row.get("tipo_documento");
        IdentificationType type = typeText == null ? null
                : TYPES.get(typeText.toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", ""));
        if (type == null) {
            throw new DomainException("INVALID_IDENTIFICATION", "tipo_documento debe ser NIT, CC, CE, PA, TI o DE");
        }
        String number = row.get("numero") == null ? null : row.get("numero").replaceAll("[.\\s]", "");
        if (number != null && type == IdentificationType.NIT && number.contains("-")) {
            number = number.substring(0, number.indexOf('-')); // "900123456-7": el DV va aparte
        }
        Integer dv = null;
        if (type == IdentificationType.NIT && number != null && number.matches("[0-9]{5,15}")) {
            dv = row.get("dv") == null ? NitCheckDigit.compute(number) : parseInt(row.get("dv"), "dv");
        }
        FiscalProfile fiscal = fiscal(row, type);
        Customer c = Customer.create(tenantId, new PartyIdentification(type, number, dv), row.get("nombre"), row.get("correo"),
                row.get("telefono"), row.get("direccion"), row.get("municipio"), fiscal);
        municipalities.requireKnown(c.cityCode());
        return c;
    }

    private static FiscalProfile fiscal(Csv.Row row, IdentificationType type) {
        String person = row.get("tipo_persona");
        String regime = row.get("regimen");
        String resp = row.get("responsabilidades");
        if (person == null && regime == null && resp == null) {
            return null; // se completa después; en práctica no hace falta
        }
        FiscalProfile.PersonType p = person == null ? (type == IdentificationType.NIT ? FiscalProfile.PersonType.LEGAL : FiscalProfile.PersonType.NATURAL)
                : switch (person.toLowerCase(Locale.ROOT).strip()) {
                    case "juridica", "jurídica", "1", "legal" -> FiscalProfile.PersonType.LEGAL;
                    case "natural", "2" -> FiscalProfile.PersonType.NATURAL;
                    default -> throw new DomainException("INVALID_FISCAL_PROFILE", "tipo_persona debe ser juridica o natural");
                };
        FiscalProfile.VatRegime r = regime == null ? FiscalProfile.VatRegime.NOT_RESPONSIBLE
                : switch (regime.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()) {
                    case "48", "responsable", "responsable de iva" -> FiscalProfile.VatRegime.RESPONSIBLE;
                    case "49", "no responsable", "no responsable de iva" -> FiscalProfile.VatRegime.NOT_RESPONSIBLE;
                    default -> throw new DomainException("INVALID_FISCAL_PROFILE", "regimen debe ser 48 (responsable) o 49 (no responsable)");
                };
        List<String> codes = resp == null ? List.of("R-99-PN") : Arrays.stream(resp.split("[|,;]")).map(String::strip)
                .filter(x -> !x.isEmpty()).toList();
        return FiscalProfile.of(p, r, codes);
    }

    private static Integer parseInt(String text, String column) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new DomainException("INVALID_IDENTIFICATION", column + " debe ser un número");
        }
    }

    /** Mensajes del dominio están en inglés para los registros; aquí se muestran tal cual con su código. */
    private static String message(DomainException e) {
        return e.getMessage() + " (" + e.code() + ")";
    }
}
