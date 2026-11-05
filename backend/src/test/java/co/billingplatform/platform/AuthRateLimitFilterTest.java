package co.billingplatform.platform;

import co.billingplatform.platform.security.AuthRateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimitFilterTest {

    private static MockHttpServletResponse call(AuthRateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    @DisplayName("limita los intentos de login por IP y por minuto, sin afectar otras IP ni otras rutas")
    void limitsPerIp() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(3, Clock.fixed(Instant.parse("2026-11-05T12:00:30Z"), ZoneOffset.UTC));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "10.0.0.1").getStatus());
        }
        MockHttpServletResponse blocked = call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");
        assertEquals(429, blocked.getStatus());
        assertEquals("60", blocked.getHeader("Retry-After"));

        assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "10.0.0.2").getStatus(), "otra IP no se ve afectada");
        assertEquals(200, call(filter, "GET", "/api/v1/public/plans", "10.0.0.1").getStatus(), "rutas públicas no se limitan");
    }

    @Test
    @DisplayName("la ventana se reinicia cada minuto")
    void windowResets() throws Exception {
        AuthRateLimitFilter early = new AuthRateLimitFilter(1, Clock.fixed(Instant.parse("2026-11-05T12:00:59Z"), ZoneOffset.UTC));
        assertEquals(200, call(early, "POST", "/api/v1/auth/register", "10.0.0.9").getStatus());
        assertEquals(429, call(early, "POST", "/api/v1/auth/register", "10.0.0.9").getStatus());
    }
}
