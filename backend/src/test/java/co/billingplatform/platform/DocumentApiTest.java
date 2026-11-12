package co.billingplatform.platform;

import co.billingplatform.platform.mail.InMemoryMailbox;
import co.billingplatform.shared.application.Mailer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Representación gráfica, enlace público y envío por correo de un documento validado (simulación). */
class DocumentApiTest extends ApiTest {

    @Autowired
    InMemoryMailbox mailbox;

    private String issueValidatedInvoice(String token, String customerEmail) throws Exception {
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        LocalDate today = LocalDate.now();
        assertEquals(201, send("POST", "/api/v1/numbering-ranges", token,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000077\",\"prefix\":\"DOC\","
                        + "\"rangeFrom\":1,\"rangeTo\":100,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(today.minusDays(2), today.plusYears(1))).statusCode());
        assertEquals(200, send("PUT", "/api/v1/organization", token, """
                {"legalName":"Hogar de Paso Doc S.A.S.","email":"emisor@hogar.example","addressLine":"Calle 1 # 2-3",
                 "cityCode":"05001","fiscal":{"personType":"LEGAL","vatRegime":"RESPONSIBLE","responsibilities":["O-47"]}}
                """).statusCode());
        String customerId = field(send("POST", "/api/v1/customers", token, "{" + randomNitJson()
                + ",\"legalName\":\"Comprador S.A.S.\",\"email\":\"" + customerEmail + "\",\"address\":\"Cra 7 # 1-1\","
                + "\"cityCode\":\"76001\",\"fiscal\":{\"personType\":\"LEGAL\",\"vatRegime\":\"NOT_RESPONSIBLE\","
                + "\"responsibilities\":[\"R-99-PN\"]}}").body(), "id");
        String id = field(send("POST", "/api/v1/invoices", token, """
                {"customerId":"%s","lines":[{"description":"Alimento","unitCode":"94","quantity":"1","unitPrice":"10000",
                 "taxRateCodes":["IVA_19"]}]}""".formatted(customerId)).body(), "id");

        assertEquals(409, send("POST", "/api/v1/invoices/" + id + "/share-links", token, null).statusCode(),
                "un borrador no se comparte");
        assertEquals(200, send("POST", "/api/v1/invoices/" + id + "/finalize", token, null).statusCode());
        long deadline = System.currentTimeMillis() + 30_000;
        while (!field(send("GET", "/api/v1/invoices/" + id, token, null).body(), "status").equals("ACCEPTED")) {
            assertTrue(System.currentTimeMillis() < deadline, "no se validó a tiempo");
            Thread.sleep(200);
        }
        return id;
    }

    @Test
    @DisplayName("representación: emisor y comprador con municipio, resolución, CUFE y QR del proveedor; aislada por tenant")
    void sheet() throws Exception {
        String token = registerOrganization();
        String id = issueValidatedInvoice(token, "comprador-" + UUID.randomUUID() + "@example.com");

        HttpResponse<String> sheet = send("GET", "/api/v1/invoices/" + id + "/sheet", token, null);
        assertEquals(200, sheet.statusCode(), sheet.body());
        String body = sheet.body();
        assertTrue(body.contains("\"cityName\":\"Medellín\""), body);
        assertTrue(body.contains("\"cityName\":\"Santiago de Cali\""), body);
        assertTrue(body.contains("\"resolutionNumber\":\"18760000077\""), body);
        assertTrue(body.contains("DOCUMENTO DE PRACTICA - SIN VALIDEZ TRIBUTARIA"), "el QR del simulador es inconfundible");
        assertEquals("false", field(body, "legallyValid"));
        assertEquals("true", field(body, "simulated"));
        assertFalse(body.contains("\"history\""), "la representación no expone el historial interno");

        assertEquals(404, send("GET", "/api/v1/invoices/" + id + "/sheet", registerOrganization(), null).statusCode());
    }

    @Test
    @DisplayName("enlace público: el comprador ve el documento sin cuenta; token falso o ajeno no sirve")
    void publicLink() throws Exception {
        String token = registerOrganization();
        String id = issueValidatedInvoice(token, "comprador-" + UUID.randomUUID() + "@example.com");

        HttpResponse<String> link = send("POST", "/api/v1/invoices/" + id + "/share-links", token, null);
        assertEquals(201, link.statusCode(), link.body());
        Matcher m = Pattern.compile("https://app\\.prueba\\.co/documento#t=([A-Za-z0-9_-]{43})").matcher(link.body());
        assertTrue(m.find(), link.body());

        HttpResponse<String> viewed = send("POST", "/api/v1/public/documents/view", null, "{\"token\":\"" + m.group(1) + "\"}");
        assertEquals(200, viewed.statusCode(), viewed.body());
        assertEquals("Hogar de Paso Doc S.A.S.", field(viewed.body(), "legalName"));
        assertTrue(viewed.headers().firstValue("Cache-Control").orElse("").contains("no-store"));

        String forged = m.group(1).substring(0, 42) + (m.group(1).endsWith("A") ? "B" : "A");
        assertEquals(404, send("POST", "/api/v1/public/documents/view", null, "{\"token\":\"" + forged + "\"}").statusCode());
        assertEquals(404, send("POST", "/api/v1/public/documents/view", null, "{\"token\":\"' OR 1=1 --\"}").statusCode());
    }

    @Test
    @DisplayName("correo al comprador con enlace y aviso de práctica; a otro correo si se indica; correo inválido se rechaza")
    void sendByEmail() throws Exception {
        String token = registerOrganization();
        String buyer = "comprador-" + UUID.randomUUID() + "@example.com";
        String id = issueValidatedInvoice(token, buyer);

        HttpResponse<String> sent = send("POST", "/api/v1/invoices/" + id + "/send", token, "{}");
        assertEquals(202, sent.statusCode(), sent.body());
        assertEquals(buyer, field(sent.body(), "sentTo"));
        Mailer.Email mail = await(buyer);
        assertTrue(mail.subject().startsWith("[Sin validez tributaria] Factura electrónica DOC1 de Hogar de Paso Doc"), mail.subject());
        assertTrue(mail.text().contains("no tiene validez tributaria"));
        assertTrue(mail.text().matches("(?s).*https://app\\.prueba\\.co/documento#t=[A-Za-z0-9_-]{43}.*"), mail.text());
        assertTrue(mail.html().contains("&amp;") || !mail.html().contains("<script"), "HTML escapado");

        String other = "contador-" + UUID.randomUUID() + "@example.com";
        assertEquals(202, send("POST", "/api/v1/invoices/" + id + "/send", token, "{\"email\":\"" + other + "\"}").statusCode());
        await(other);
        assertEquals(422, send("POST", "/api/v1/invoices/" + id + "/send", token, "{\"email\":\"no-es-correo\"}").statusCode());
    }

    private Mailer.Email await(String to) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (mailbox.countTo(to) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return mailbox.lastTo(to).orElseThrow(() -> new AssertionError("no llegó correo a " + to));
    }
}
