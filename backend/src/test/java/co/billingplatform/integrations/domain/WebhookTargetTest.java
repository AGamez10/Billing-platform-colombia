package co.billingplatform.integrations.domain;

import co.billingplatform.shared.domain.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookTargetTest {

    @Test
    @DisplayName("solo HTTPS a dominios públicos: sin IP, localhost, dominios internos, credenciales ni puertos raros")
    void validates() {
        assertEquals("https://hooks.adoptafacil.co/facturas", WebhookTarget.validate(" https://hooks.adoptafacil.co/facturas ").toString());
        for (String bad : new String[] {"http://hooks.example.com/x", "https://127.0.0.1/x", "https://10.0.0.5/x", "https://[::1]/x",
                "https://localhost/x", "https://api.internal/x", "https://printer.local/x", "https://intranet/x",
                "https://user:pass@hooks.example.com/x", "https://hooks.example.com:22/x", "https://hooks.example.com/x#frag",
                "ftp://hooks.example.com", "https://169.254.169.254/latest/meta-data", ""}) {
            assertEquals("INVALID_WEBHOOK_URL", assertThrows(DomainException.class, () -> WebhookTarget.validate(bad)).code(), bad);
        }
    }

    @Test
    @DisplayName("firma: t=epoch,v1=HMAC-SHA256(secreto, \"epoch.cuerpo\") en hexadecimal")
    void signs() {
        String h = WebhookSignature.header("whsec_x", 1700000000L, "{\"a\":1}");
        assertEquals("t=1700000000,v1=" + WebhookSignature.hmac("whsec_x", "1700000000.{\"a\":1}"), h);
        assertTrue(h.matches("t=1700000000,v1=[0-9a-f]{64}"));
        assertNotEquals(h, WebhookSignature.header("otro", 1700000000L, "{\"a\":1}"));
    }
}
