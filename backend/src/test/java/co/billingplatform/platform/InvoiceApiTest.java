package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Facturas por HTTP real, de punta a punta con el proveedor simulado: borrador → emisión →
 * envío por la cola → aceptación simulada, sin efecto tributario.
 */
class InvoiceApiTest extends ApiTest {

    private String setUpIssuer(String token) throws Exception {
        HttpResponse<String> account = send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}");
        assertEquals(201, account.statusCode(), account.body());
        assertEquals("true", field(account.body(), "simulated"));

        LocalDate today = LocalDate.now();
        HttpResponse<String> range = send("POST", "/api/v1/numbering-ranges", token,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000001\",\"prefix\":\"sett\","
                        + "\"rangeFrom\":1,\"rangeTo\":5000,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(today.minusDays(2), today.plusYears(1)));
        assertEquals(201, range.statusCode(), range.body());
        assertEquals("SETT", field(range.body(), "prefix"), "el prefijo se normaliza a mayúsculas");

        HttpResponse<String> customer = send("POST", "/api/v1/customers", token,
                "{" + randomNitJson() + ",\"legalName\":\"Cliente Factura S.A.S.\",\"email\":\"f@cliente.co\"}");
        assertEquals(201, customer.statusCode(), customer.body());
        return field(customer.body(), "id");
    }

    private static String draftJson(String customerId) {
        return """
                {"customerId":"%s","lines":[
                  {"description":"Consulta veterinaria","unitCode":"94","quantity":"2","unitPrice":"50000",
                   "discount":{"kind":"PERCENT","value":"10"},"taxRateCodes":["IVA_19"]},
                  {"description":"Libro","unitCode":"94","quantity":"1","unitPrice":"40000.50","taxRateCodes":["IVA_EXCLUIDO"]}
                ]}""".formatted(customerId);
    }

    private HttpResponse<String> awaitFinalStatus(String token, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        HttpResponse<String> res;
        do {
            res = send("GET", "/api/v1/invoices/" + id, token, null);
            String status = field(res.body(), "status");
            if (status.equals("ACCEPTED") || status.equals("REJECTED") || status.equals("FAILED")) {
                return res;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        fail("la factura no llegó a un estado final: " + res.body());
        return res;
    }

    @Test
    @DisplayName("borrador → emitir → enviado por la cola → aceptado en SIMULACIÓN, nunca con validez legal")
    void issueInvoiceEndToEnd() throws Exception {
        String token = registerOrganization();
        String customerId = setUpIssuer(token);

        HttpResponse<String> draft = send("POST", "/api/v1/invoices", token, draftJson(customerId));
        assertEquals(201, draft.statusCode(), draft.body());
        String id = field(draft.body(), "id");
        assertEquals("DRAFT", field(draft.body(), "status"));
        assertTrue(draft.body().contains("\"number\":null"), "un borrador no consume número");
        // 2 × 50.000 = 100.000 − 10 % = 90.000 + IVA 19 % 17.100 = 107.100; libro excluido 40.000,50
        assertEquals("147100.50", field(draft.body(), "payableAmount"));
        assertEquals("17100.00", field(draft.body(), "taxTotal"));

        HttpResponse<String> edited = send("PUT", "/api/v1/invoices/" + id, token, draftJson(customerId));
        assertEquals(200, edited.statusCode(), edited.body());

        HttpResponse<String> issued = send("POST", "/api/v1/invoices/" + id + "/finalize", token, null);
        assertEquals(200, issued.statusCode(), issued.body());
        assertEquals("SETT1", field(issued.body(), "number"));
        assertEquals("SIMULATION", field(issued.body(), "environment"));

        HttpResponse<String> again = send("POST", "/api/v1/invoices/" + id + "/finalize", token, null);
        assertEquals(200, again.statusCode());
        assertEquals("SETT1", field(again.body(), "number"), "repetir la emisión no asigna otro número");

        HttpResponse<String> done = awaitFinalStatus(token, id);
        assertEquals("ACCEPTED", field(done.body(), "status"), done.body());
        assertTrue(field(done.body(), "documentKey").startsWith("SIMULATED-"), done.body());
        assertEquals("false", field(done.body(), "legallyValid"), "una aceptación simulada nunca tiene validez legal");
        assertEquals("true", field(done.body(), "simulated"));
        assertEquals("147100.50", field(done.body(), "payableAmount"), "los totales sobreviven a la persistencia");

        assertEquals(409, send("PUT", "/api/v1/invoices/" + id, token, draftJson(customerId)).statusCode(),
                "una factura emitida es inmutable");
        HttpResponse<String> deleteIssued = send("DELETE", "/api/v1/invoices/" + id, token, null);
        assertEquals(409, deleteIssued.statusCode());
        assertEquals("INVALID_STATE_TRANSITION", field(deleteIssued.body(), "code"));

        HttpResponse<String> list = send("GET", "/api/v1/invoices?status=ACCEPTED", token, null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains(id), list.body());

        HttpResponse<String> second = send("POST", "/api/v1/invoices", token, draftJson(customerId));
        String secondId = field(second.body(), "id");
        assertEquals("SETT2", field(send("POST", "/api/v1/invoices/" + secondId + "/finalize", token, null).body(), "number"),
                "numeración consecutiva sin huecos");

        HttpResponse<String> quota = send("GET", "/api/v1/subscription/quota", token, null);
        assertEquals(200, quota.statusCode(), quota.body());
        assertEquals("0", field(quota.body(), "used"), "la simulación no consume documentos del plan");
    }

    @Test
    @DisplayName("borradores: se borran; sin proveedor o sin resolución no se emite")
    void draftsAndPreconditions() throws Exception {
        String token = registerOrganization();
        String customerId = field(send("POST", "/api/v1/customers", token,
                "{" + randomNitJson() + ",\"legalName\":\"Cliente\"}").body(), "id");
        String id = field(send("POST", "/api/v1/invoices", token, draftJson(customerId)).body(), "id");

        HttpResponse<String> noProvider = send("POST", "/api/v1/invoices/" + id + "/finalize", token, null);
        assertEquals(409, noProvider.statusCode());
        assertEquals("NO_PROVIDER_CONFIGURED", field(noProvider.body(), "code"));

        send("POST", "/api/v1/provider-accounts", token, "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}");
        HttpResponse<String> noRange = send("POST", "/api/v1/invoices/" + id + "/finalize", token, null);
        assertEquals(409, noRange.statusCode());
        assertEquals("NUMBERING_RANGE_NOT_FOUND", field(noRange.body(), "code"));

        HttpResponse<String> mockInProduction = send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"PRODUCTION\"}");
        assertEquals(422, mockInProduction.statusCode(), "el simulado nunca opera en producción");

        assertEquals(204, send("DELETE", "/api/v1/invoices/" + id, token, null).statusCode());
        assertEquals(404, send("GET", "/api/v1/invoices/" + id, token, null).statusCode());

        HttpResponse<String> badDiscount = send("POST", "/api/v1/invoices", token, """
                {"customerId":"%s","lines":[{"description":"X","unitCode":"94","quantity":"1","unitPrice":"10",
                 "discount":{"kind":"PERCENT","value":"150"}}]}""".formatted(customerId));
        assertEquals(422, badDiscount.statusCode());
        assertEquals(400, send("POST", "/api/v1/invoices", token, "{\"customerId\":\"" + customerId + "\",\"lines\":[]}").statusCode());
    }

    @Test
    @DisplayName("aislamiento: otra organización no ve, no edita, no emite ni borra mis facturas, ni usa mis clientes")
    void tenantIsolation() throws Exception {
        String a = registerOrganization();
        String b = registerOrganization();
        String customerOfA = setUpIssuer(a);
        setUpIssuer(b);
        String id = field(send("POST", "/api/v1/invoices", a, draftJson(customerOfA)).body(), "id");

        assertEquals(404, send("GET", "/api/v1/invoices/" + id, b, null).statusCode());
        assertEquals(404, send("PUT", "/api/v1/invoices/" + id, b, draftJson(customerOfA)).statusCode());
        assertEquals(404, send("POST", "/api/v1/invoices/" + id + "/finalize", b, null).statusCode());
        assertEquals(404, send("DELETE", "/api/v1/invoices/" + id, b, null).statusCode());
        assertFalse(send("GET", "/api/v1/invoices", b, null).body().contains(id));

        HttpResponse<String> foreignCustomer = send("POST", "/api/v1/invoices", b, draftJson(customerOfA));
        assertEquals(404, foreignCustomer.statusCode(), "no se puede facturar a un cliente de otra organización");
        assertEquals("CUSTOMER_NOT_FOUND", field(foreignCustomer.body(), "code"));

        assertEquals("DRAFT", field(send("GET", "/api/v1/invoices/" + id, a, null).body(), "status"), "la factura de A sigue intacta");
    }
}
