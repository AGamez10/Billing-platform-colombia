package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ImportAndReportApiTest extends ApiTest {

    private HttpResponse<String> postCsv(String path, String token, String csv) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port") + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "text/csv; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("importar clientes: vista previa no guarda; errores por línea; repetidos y existentes no se duplican")
    void importCustomers() throws Exception {
        String token = registerOrganization();
        String csv = """
                Tipo documento;Número;DV;Nombre;Correo;Municipio;Tipo persona;Régimen;Responsabilidades
                NIT;900.123.456;;Comercial Uno S.A.S.;uno@ejemplo.co;11001;juridica;48;O-13|O-23
                CC;1020304050;;Ana Pérez;;;;;
                CC;1020304050;;Ana Repetida;;;;;
                XX;123;;Tipo malo;;;;;
                NIT;800197268;3;DV malo;;;;;
                CC;55555;;Municipio malo;;99999;;;
                """;
        HttpResponse<String> preview = postCsv("/api/v1/customers/import", token, csv);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(preview.body().contains("\"committed\":false") && preview.body().contains("\"valid\":2"), preview.body());
        assertTrue(preview.body().contains("\"line\":4") && preview.body().contains("\"line\":5") && preview.body().contains("\"line\":6")
                && preview.body().contains("\"line\":7"), preview.body());
        assertTrue(send("GET", "/api/v1/customers?q=Comercial", token, null).body().contains("\"items\":[]"), "la vista previa no guarda");

        HttpResponse<String> done = postCsv("/api/v1/customers/import?commit=true", token, csv);
        assertTrue(done.body().contains("\"created\":2"), done.body());
        String found = send("GET", "/api/v1/customers?q=Comercial", token, null).body();
        assertTrue(found.contains("\"checkDigit\":") && found.contains("O-23"), "DV calculado y perfil importado: " + found);
        assertTrue(postCsv("/api/v1/customers/import?commit=true", token, csv).body().contains("\"created\":0"), "no se duplican");
    }

    @Test
    @DisplayName("importar productos con precios en formato colombiano; impuestos obligatorios; plantilla pública")
    void importProducts() throws Exception {
        String token = registerOrganization();
        HttpResponse<String> r = postCsv("/api/v1/products/import?commit=true", token, """
                nombre,sku,tipo,unidad,precio,impuestos
                Collar,COL-1,producto,94,"25.000",IVA_19
                Consulta,SER-1,servicio,94,60000,IVA_EXCLUIDO
                Sin impuesto,X-1,producto,94,1000,
                """);
        assertTrue(r.body().contains("\"created\":2") && r.body().contains("\"line\":4"), r.body());
        assertTrue(send("GET", "/api/v1/products?q=Collar", token, null).body().contains("25000"));
        HttpResponse<String> template = send("GET", "/api/v1/public/import-templates/customers.csv", null, null);
        assertEquals(200, template.statusCode());
        assertTrue(template.body().contains("tipo_documento;numero;dv;nombre"));
    }

    @Test
    @DisplayName("reporte: totales e impuestos del periodo; CSV con punto y coma; periodo inválido rechazado")
    void report() throws Exception {
        String token = registerOrganization();
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token, "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        LocalDate today = LocalDate.now();
        assertEquals(201, send("POST", "/api/v1/numbering-ranges", token,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000041\",\"prefix\":\"RP\",\"rangeFrom\":1,\"rangeTo\":100,"
                        + "\"validFrom\":\"%s\",\"validTo\":\"%s\"}").formatted(today.minusDays(2), today.plusYears(1))).statusCode());
        String customerId = field(send("POST", "/api/v1/customers", token, "{" + randomNitJson() + ",\"legalName\":\"=CMD()\"}").body(), "id");
        String id = field(send("POST", "/api/v1/invoices", token, """
                {"customerId":"%s","lines":[{"description":"A","unitCode":"94","quantity":"1","unitPrice":"100000","taxRateCodes":["IVA_19"]},
                 {"description":"B","unitCode":"94","quantity":"1","unitPrice":"50000","taxRateCodes":["IVA_EXCLUIDO"]}]}""".formatted(customerId)).body(), "id");
        send("POST", "/api/v1/invoices/" + id + "/finalize", token, null);
        long deadline = System.currentTimeMillis() + 30_000;
        while (!field(send("GET", "/api/v1/invoices/" + id, token, null).body(), "status").equals("ACCEPTED")) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.sleep(200);
        }
        String from = today.minusDays(3).toString(), to = today.plusDays(1).toString();
        String summary = send("GET", "/api/v1/reports/sales?from=" + from + "&to=" + to, token, null).body();
        assertTrue(summary.contains("\"documents\":1") && summary.contains("\"total\":169000.00"), summary);
        assertTrue(summary.contains("\"amount\":19000.00"), summary);
        HttpResponse<String> csv = send("GET", "/api/v1/reports/sales.csv?from=" + from + "&to=" + to, token, null);
        assertEquals(200, csv.statusCode());
        assertTrue(csv.body().contains(";RP1;") && csv.body().contains(";169000,00;"), csv.body());
        assertTrue(csv.body().contains(";'=CMD();"), "fórmula neutralizada: " + csv.body());
        assertEquals(422, send("GET", "/api/v1/reports/sales?from=" + to + "&to=" + from, token, null).statusCode());
    }
}
