package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Notas crédito de punta a punta con el proveedor simulado. */
class CreditNoteApiTest extends ApiTest {

    private void accepted(String token, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!field(send("GET", "/api/v1/invoices/" + id, token, null).body(), "status").equals("ACCEPTED")) {
            assertTrue(System.currentTimeMillis() < deadline, "no se validó a tiempo: " + id);
            Thread.sleep(200);
        }
    }

    private void range(String token, String kind, String prefix) throws Exception {
        LocalDate today = LocalDate.now();
        HttpResponse<String> r = send("POST", "/api/v1/numbering-ranges", token,
                ("{\"kind\":\"%s\",\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000011\",\"prefix\":\"%s\","
                        + "\"rangeFrom\":1,\"rangeTo\":100,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(kind, prefix, today.minusDays(2), today.plusYears(1)));
        assertEquals(201, r.statusCode(), r.body());
    }

    @Test
    @DisplayName("devolución parcial y luego el resto; no se anula lo ya acreditado; la nota referencia la factura")
    void creditNotes() throws Exception {
        String token = registerOrganization();
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        range(token, "INVOICE", "FV");
        String customerId = field(send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"Comprador NC\"}").body(), "id");
        String invoice = field(send("POST", "/api/v1/invoices", token, """
                {"customerId":"%s","lines":[
                  {"description":"Collar","unitCode":"94","quantity":"3","unitPrice":"10000","taxRateCodes":["IVA_19"]},
                  {"description":"Consulta","unitCode":"94","quantity":"1","unitPrice":"50000","taxRateCodes":["IVA_EXCLUIDO"]}]}
                """.formatted(customerId)).body(), "id");

        assertEquals(409, send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", token,
                "{\"concept\":\"ANNULMENT\",\"description\":\"Borrador\"}").statusCode(), "un borrador no se corrige con nota");
        assertEquals(200, send("POST", "/api/v1/invoices/" + invoice + "/finalize", token, null).statusCode());
        accepted(token, invoice);

        HttpResponse<String> partial = send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", token,
                "{\"concept\":\"RETURN\",\"description\":\"Devolvió un collar\",\"lines\":[{\"lineNumber\":1,\"quantity\":\"1\"}]}");
        assertEquals(201, partial.statusCode(), partial.body());
        String note = field(partial.body(), "id");
        assertEquals("CREDIT_NOTE", field(partial.body(), "kind"));
        assertEquals("11900.00", field(partial.body(), "payableAmount"));
        assertEquals("FV1", field(partial.body(), "creditedNumber"));

        HttpResponse<String> noRange = send("POST", "/api/v1/invoices/" + note + "/finalize", token, null);
        assertEquals(409, noRange.statusCode(), "sin numeración de notas no se emite: " + noRange.body());
        range(token, "CREDIT_NOTE", "NC");
        assertEquals(200, send("POST", "/api/v1/invoices/" + note + "/finalize", token, null).statusCode());
        accepted(token, note);
        assertEquals("NC1", field(send("GET", "/api/v1/invoices/" + note, token, null).body(), "number"));
        String sheet = send("GET", "/api/v1/invoices/" + note + "/sheet", token, null).body();
        assertTrue(sheet.contains("\"creditedNumber\":\"FV1\"") && sheet.contains("\"concept\":\"RETURN\""), sheet);

        assertEquals(409, send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", token,
                "{\"concept\":\"ANNULMENT\",\"description\":\"Anular todo\"}").statusCode());
        assertEquals(422, send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", token,
                "{\"concept\":\"RETURN\",\"description\":\"De más\",\"lines\":[{\"lineNumber\":1,\"quantity\":\"3\"}]}").statusCode());
        assertEquals(201, send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", token,
                "{\"concept\":\"RETURN\",\"description\":\"El resto\",\"lines\":[{\"lineNumber\":1,\"quantity\":\"2\"},"
                        + "{\"lineNumber\":2,\"quantity\":\"1\"}]}").statusCode());

        String list = send("GET", "/api/v1/invoices/" + invoice + "/credit-notes", token, null).body();
        assertEquals(2, list.split("\"kind\":\"CREDIT_NOTE\"", -1).length - 1, list);
        assertEquals(404, send("POST", "/api/v1/invoices/" + invoice + "/credit-notes", registerOrganization(),
                "{\"concept\":\"ANNULMENT\",\"description\":\"Ajena\"}").statusCode(), "otra organización no la ve");
        assertEquals(409, send("PUT", "/api/v1/invoices/" + note, token, "{\"customerId\":\"" + customerId
                + "\",\"lines\":[{\"description\":\"x\",\"unitCode\":\"94\",\"quantity\":\"1\",\"unitPrice\":\"1\",\"taxRateCodes\":[\"IVA_19\"]}]}")
                .statusCode(), "una nota no se edita como factura");
    }
}
