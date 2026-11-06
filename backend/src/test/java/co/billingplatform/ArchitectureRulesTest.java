package co.billingplatform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reglas de dependencia entre capas y módulos, verificadas sobre las importaciones del
 * código fuente. Se complementarán con ArchUnit (ya declarado en el POM) cuando haya
 * acceso a Maven Central; estas reglas no necesitan dependencias.
 */
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src/main/java/co/billingplatform");

    private static List<String> violations(String packageGlob, List<String> forbiddenImportPrefixes) {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> MAIN.relativize(p).toString().replace('\\', '/').matches(packageGlob))
                 .forEach(p -> {
                     try {
                         for (String line : Files.readAllLines(p)) {
                             String l = line.strip();
                             if (!l.startsWith("import ")) continue;
                             for (String forbidden : forbiddenImportPrefixes) {
                                 if (l.startsWith("import " + forbidden) || l.startsWith("import static " + forbidden)) {
                                     found.add(MAIN.relativize(p) + " -> " + l);
                                 }
                             }
                         }
                     } catch (IOException e) {
                         throw new UncheckedIOException(e);
                     }
                 });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    @Test
    @DisplayName("dominio y aplicación no dependen de frameworks ni de persistencia")
    void coreIsFrameworkFree() {
        List<String> v = violations("[a-z]+/(domain|application|port)/.*",
                List.of("org.springframework", "jakarta.persistence", "jakarta.servlet", "org.hibernate", "com.fasterxml"));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("el núcleo nunca depende de un adaptador de proveedor concreto")
    void coreDoesNotDependOnAdapters() {
        List<String> v = violations("(?!electronicinvoicing/adapter/).*",
                List.of("co.billingplatform.electronicinvoicing.adapter"));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("electronicinvoicing es un módulo de contrato: no conoce facturación ni impuestos")
    void electronicInvoicingDoesNotDependOnInvoicing() {
        List<String> v = violations("electronicinvoicing/.*",
                List.of("co.billingplatform.invoicing", "co.billingplatform.tax"));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("el motor tributario no depende de facturación ni de proveedores")
    void taxIsIndependent() {
        List<String> v = violations("tax/.*",
                List.of("co.billingplatform.invoicing", "co.billingplatform.electronicinvoicing"));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("facturación no conoce planes ni identidad: los usa solo a través de sus puertos")
    void invoicingDoesNotDependOnSubscriptionsOrIdentity() {
        List<String> v = violations("invoicing/.*", List.of("co.billingplatform.subscriptions"));
        // La capa web de cada módulo exige permisos (vocabulario común de autorización, igual que
        // clientes y productos); el núcleo de facturación no conoce identidad en absoluto.
        v.addAll(violations("invoicing/(domain|application)/.*", List.of("co.billingplatform.identity")));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("cobros depende de facturación, nunca al revés; y su núcleo no conoce la pasarela concreta")
    void paymentsIsOneDirectional() {
        List<String> v = violations("invoicing/.*", List.of("co.billingplatform.payments"));
        v.addAll(violations("payments/(domain|application|port)/.*", List.of("co.billingplatform.payments.adapter")));
        assertTrue(v.isEmpty(), "Violaciones: " + v);
    }

    @Test
    @DisplayName("el núcleo no usa float/double para dinero")
    void noFloatingPointInCore() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String src = Files.readString(p);
                    if (src.matches("(?s).*\\b(double|float|Double|Float)\\b.*")) {
                        found.add(MAIN.relativize(p).toString());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(found.isEmpty(), "Uso de punto flotante en: " + found);
    }
}
