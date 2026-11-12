package co.billingplatform.platform.catalog;

import co.billingplatform.shared.application.Municipalities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DIVIPOLA cargada en memoria desde {@code catalog/divipola.csv}: el archivo lo genera el flujo
 * "Catálogo DIVIPOLA" a partir de los datos abiertos del DANE (datos.gov.co, gdxc-w37w) y queda
 * versionado en el repositorio. Unas 1.100 filas: no amerita tabla ni consulta a la base.
 */
public final class DivipolaCatalog implements Municipalities {

    private final Map<String, Municipality> byCode;
    private final List<Municipality> all;

    public DivipolaCatalog(Map<String, Municipality> byCode) {
        this.byCode = Map.copyOf(byCode);
        this.all = List.copyOf(byCode.values());
    }

    public static DivipolaCatalog fromClasspath() {
        try (InputStream in = DivipolaCatalog.class.getResourceAsStream("/catalog/divipola.csv")) {
            if (in == null) {
                throw new IllegalStateException("catalog/divipola.csv is missing from the classpath");
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static DivipolaCatalog parse(BufferedReader reader) throws IOException {
        Map<String, Municipality> map = new LinkedHashMap<>();
        String header = reader.readLine();
        if (header == null || !header.strip().equals("code,name,department_code,department_name")) {
            throw new IllegalStateException("Unexpected DIVIPOLA header");
        }
        String row;
        int line = 1;
        while ((row = reader.readLine()) != null) {
            line++;
            if (row.isBlank()) continue;
            List<String> cells = cells(row);
            if (cells.size() != 4 || !cells.get(0).matches("[0-9]{5}") || !cells.get(2).equals(cells.get(0).substring(0, 2))) {
                throw new IllegalStateException("Invalid DIVIPOLA row " + line);
            }
            map.put(cells.get(0), new Municipality(cells.get(0), title(cells.get(1)), cells.get(2), title(cells.get(3))));
        }
        if (map.size() < 1000) {
            throw new IllegalStateException("DIVIPOLA looks incomplete: " + map.size() + " municipalities");
        }
        return new DivipolaCatalog(map);
    }

    /** CSV con campos entre comillas (los nombres llevan comas: "BOGOTÁ, D.C."). */
    private static List<String> cells(String row) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < row.length() && row.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().strip());
        return out;
    }

    /** "SANTIAGO DE CALI" → "Santiago de Cali"; respeta "D.C." y conectores en minúscula. */
    static String title(String upper) {
        StringBuilder sb = new StringBuilder();
        String[] words = upper.toLowerCase(java.util.Locale.ROOT).split(" ");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (i > 0) sb.append(' ');
            if (w.isEmpty()) continue;
            if (w.equals("d.c.") || w.equals("d.c")) { sb.append(w.toUpperCase(java.util.Locale.ROOT)); continue; }
            if (i > 0 && List.of("de", "del", "la", "las", "los", "el", "y", "e").contains(w)) { sb.append(w); continue; }
            sb.append(capitalize(w));
        }
        return sb.toString();
    }

    private static String capitalize(String w) {
        // También tras guion o paréntesis: "(caruru)" → "(Caruru)", "sabana-larga" → "Sabana-Larga".
        StringBuilder sb = new StringBuilder(w.length());
        boolean up = true;
        for (char c : w.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == '-' || c == '(' || c == '/';
        }
        return sb.toString();
    }

    @Override
    public Optional<Municipality> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code.strip()));
    }

    @Override
    public List<Municipality> all() {
        return all;
    }
}
