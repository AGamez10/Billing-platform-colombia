package co.billingplatform.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/** Oferta de mejora de plan: se registra la elección con el precio cotizado; el plan no cambia sin pago. */
class PlanChangeApiTest extends ApiTest {

    @Test
    @DisplayName("pedir mejora de plan: precio del ciclo, reemplaza la anterior y no cambia el plan sin pago")
    void requestUpgrade() throws Exception {
        String token = registerOrganization();
        assertEquals("false", field(send("GET", "/api/v1/subscription/plan-change-requests/pending", token, null).body(), "pending"));

        HttpResponse<String> monthly = send("POST", "/api/v1/subscription/plan-change-requests", token,
                "{\"planCode\":\"COMERCIO_1200\",\"cycle\":\"MONTHLY\"}");
        assertEquals(201, monthly.statusCode(), monthly.body());
        assertEquals("64900.00", field(monthly.body(), "quotedPrice"));
        assertEquals("TRIAL", field(monthly.body(), "fromPlan"));

        HttpResponse<String> microMonthly = send("POST", "/api/v1/subscription/plan-change-requests", token,
                "{\"planCode\":\"MICRO\",\"cycle\":\"MONTHLY\"}");
        assertEquals(422, microMonthly.statusCode());
        assertEquals("PLAN_CYCLE_NOT_AVAILABLE", field(microMonthly.body(), "code"));
        assertEquals(404, send("POST", "/api/v1/subscription/plan-change-requests", token,
                "{\"planCode\":\"NO_EXISTE\",\"cycle\":\"ANNUAL\"}").statusCode());

        HttpResponse<String> annual = send("POST", "/api/v1/subscription/plan-change-requests", token,
                "{\"planCode\":\"MICRO\",\"cycle\":\"ANNUAL\"}");
        assertEquals(201, annual.statusCode(), annual.body());
        HttpResponse<String> pending = send("GET", "/api/v1/subscription/plan-change-requests/pending", token, null);
        assertEquals("MICRO", field(pending.body(), "toPlan"), "la última elección reemplaza a la anterior");
        assertEquals("69000.00", field(pending.body(), "quotedPrice"));

        assertEquals("TRIAL", field(send("GET", "/api/v1/subscription/quota", token, null).body(), "planCode"),
                "el plan no cambia hasta confirmar el pago");
        assertEquals("false", field(send("GET", "/api/v1/subscription/plan-change-requests/pending", registerOrganization(), null)
                .body(), "pending"), "otra organización no ve la solicitud");
    }
}
