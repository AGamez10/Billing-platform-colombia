package co.billingplatform.shared.csv;

import co.billingplatform.shared.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvTest {

    @Test
    @DisplayName("lee punto y coma o coma, comillas, BOM y encabezados con tildes")
    void reads() {
        List<Csv.Row> rows = Csv.read("﻿Nombre;Número;Correo electrónico\r\n\"Pérez; Ana\";123;ana@x.co\r\n;;\r\nLuis;456;\n");
        assertEquals(2, rows.size());
        assertEquals("Pérez; Ana", rows.getFirst().get("nombre"));
        assertEquals("123", rows.getFirst().get("numero"));
        assertEquals("ana@x.co", rows.getFirst().get("correo_electronico"));
        assertEquals(2, rows.getFirst().line());
        assertNull(rows.get(1).get("correo_electronico"));
        assertEquals(4, rows.get(1).line(), "la línea vacía se salta pero se cuenta");

        List<Csv.Row> comma = Csv.read("nombre,precio\n\"Collar, rojo\",\"10,5\"");
        assertEquals("Collar, rojo", comma.getFirst().get("nombre"));
        assertEquals("10,5", comma.getFirst().get("precio"));
    }

    @Test
    @DisplayName("límites: vacío y demasiado grande se rechazan")
    void limits() {
        assertEquals("IMPORT_EMPTY", assertThrows(DomainException.class, () -> Csv.read("  ")).code());
        assertEquals("IMPORT_TOO_LARGE", assertThrows(DomainException.class,
                () -> Csv.read("a\n" + "x\n".repeat(Csv.MAX_ROWS + 1))).code());
    }

    @Test
    @DisplayName("al escribir neutraliza fórmulas (inyección CSV) pero no números negativos")
    void writesSafely() {
        String out = Csv.write(List.of("a", "b"), List.of(List.of("=HYPERLINK(\"http://x\")", "-119000,00"),
                List.of("@SUM(1)", "texto; con punto y coma")));
        assertTrue(out.startsWith("﻿a;b\r\n"));
        assertTrue(out.contains("\"'=HYPERLINK(\"\"http://x\"\")\";-119000,00\r\n"), out);
        assertTrue(out.contains("'@SUM(1);\"texto; con punto y coma\""), out);
        assertEquals("'+57 300", Csv.cell("+57 300"));
    }
}
