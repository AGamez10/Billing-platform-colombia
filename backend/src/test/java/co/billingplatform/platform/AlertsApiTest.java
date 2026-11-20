package co.billingplatform.platform;

import co.billingplatform.insights.application.AlertNotifier;
import co.billingplatform.platform.mail.InMemoryMailbox;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertsApiTest extends ApiTest {

    @Autowired AlertNotifier notifier;
    @Autowired TenantTransactions tx;
    @Autowired InMemoryMailbox mailbox;

    @Test
    @DisplayName("aviso por correo a dueños: numeración por vencer, una sola vez; apagado no llega")
    void alerts() throws Exception {
        String owner = "dueno-" + UUID.randomUUID() + "@example.com";
        String token = registerOrganization(owner);
        TenantId tenant = new TenantId(UUID.fromString(field(send("GET", "/api/v1/auth/me", token, null).body(), "organizationId")));
        assertEquals(201, send("POST", "/api/v1/provider-accounts", token,
                "{\"providerId\":\"mock\",\"environment\":\"SIMULATION\"}").statusCode());
        LocalDate today = LocalDate.now();
        assertEquals(201, send("POST", "/api/v1/numbering-ranges", token,
                ("{\"environment\":\"SIMULATION\",\"resolutionNumber\":\"18760000031\",\"prefix\":\"AV\","
                        + "\"rangeFrom\":1,\"rangeTo\":500,\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(today.minusDays(2), today.plusDays(5))).statusCode());

        int sent = tx.inTenant(tenant, () -> notifier.notify(tenant));
        assertEquals(1, sent, "solo la numeración por vencer amerita correo (el perfil en práctica es informativo)");
        var mail = mailbox.lastTo(owner).orElseThrow();
        assertTrue(mail.subject().startsWith("La numeración de facturas (AV) vence en"), mail.subject());
        assertTrue(mail.text().contains("https://app.prueba.co/configuracion"));
        assertEquals(0, tx.inTenant(tenant, () -> notifier.notify(tenant)), "no se repite");
        assertEquals(1, mailbox.countTo(owner));

        assertEquals(204, send("PUT", "/api/v1/organization/email-alerts", token, "{\"enabled\":false}").statusCode());
        assertTrue(send("GET", "/api/v1/organization", token, null).body().contains("\"emailAlerts\":false"));
    }
}
