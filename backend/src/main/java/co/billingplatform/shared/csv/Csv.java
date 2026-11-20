package co.billingplatform.shared.csv;

import co.billingplatform.shared.domain.DomainException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSV de importación y exportación (RFC 4180 con comillas). Al leer detecta el separador
 * (punto y coma, coma o tabulador, como exportan Excel y Google Sheets en español) y normaliza
 * los encabezados (minúsculas, sin tildes, espacios → guion bajo). Al escribir usa punto y coma
 * y neutraliza celdas que empiezan como fórmula (=, +, -, @) para que una hoja de cálculo no las
 * ejecute (inyección CSV).
 */
public final class Csv {

    public static final int MAX_ROWS = 2_000;
    public static final int MAX_CHARS = 1_000_000;

    /** @param line número de línea en el archivo (1 = encabezado) */
    public record Row(int line, Map<String, String> values) {
        public String get(String column) {
            String v = values.get(column);
            return v == null || v.isBlank() ? null : v.strip();
        }
    }

    private Csv() {}

    public static List<Row> read(String text) {
        if (text == null || text.isBlank()) {
            throw new DomainException("IMPORT_EMPTY", "The file is empty");
        }
        if (text.length() > MAX_CHARS) {
            throw new DomainException("IMPORT_TOO_LARGE", "The file is larger than 1 MB");
        }
        if (text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<List<String>> records = records(text, delimiter(text));
        if (records.isEmpty()) {
            throw new DomainException("IMPORT_EMPTY", "The file is empty");
        }
        List<String> header = records.getFirst().stream().map(Csv::normalize).toList();
        if (records.size() - 1 > MAX_ROWS) {
            throw new DomainException("IMPORT_TOO_LARGE", "At most " + MAX_ROWS + " rows per file");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(String::isBlank)) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                values.put(header.get(c), c < cells.size() ? cells.get(c) : "");
            }
            rows.add(new Row(i + 1, values));
        }
        return rows;
    }

    static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        long semi = first.chars().filter(ch -> ch == ';').count();
        long comma = first.chars().filter(ch -> ch == ',').count();
        long tab = first.chars().filter(ch -> ch == '\t').count();
        if (tab > semi && tab > comma) return '\t';
        return semi >= comma && semi > 0 ? ';' : ',';
    }

    private static List<List<String>> records(String text, char sep) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    static String normalize(String header) {
        String n = Normalizer.normalize(header.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    /** Escribe una tabla con punto y coma y fin de línea CRLF, con BOM para que Excel lea UTF-8. */
    public static String write(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        line(sb, header);
        for (List<String> r : rows) line(sb, r);
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    static String cell(String value) {
        if (value == null) return "";
        String v = value;
        // Un número negativo legítimo (nota crédito) no es fórmula; todo lo demás que empiece así, sí.
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?[0-9]+([.,][0-9]+)?")) {
            v = "'" + v;
        }
        if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
