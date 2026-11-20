package co.billingplatform.platform;

import co.billingplatform.platform.security.PrincipalRateLimitFilter;
import co.billingplatform.platform.web.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalRateLimitFilterTest {

    private final PrincipalRateLimitFilter filter =
            new PrincipalRateLimitFilter(2, 3, Clock.fixed(Instant.parse("2026-11-19T12:00:15Z"), ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int call(String subject, boolean apiKey) throws Exception {
        if (subject == null) {
            SecurityContextHolder.clearContext();
        } else {
            Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject);
            if (apiKey) jwt.claim(CurrentUser.API_KEY_CLAIM, true);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/invoices"), res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    @DisplayName("limita por clave de API y por persona, cada una con su cupo y sin afectar a otras")
    void limitsPerCredential() throws Exception {
        assertEquals(200, call("key-1", true));
        assertEquals(200, call("key-1", true));
        assertEquals(429, call("key-1", true), "tercera petición de la clave en el mismo minuto");
        assertEquals(200, call("key-2", true), "otra clave tiene su propio cupo");
        for (int i = 0; i < 3; i++) {
            assertEquals(200, call("user-1", false));
        }
        assertEquals(429, call("user-1", false));
        assertEquals(200, call("key-1-as-user", false));
        assertEquals(200, call(null, false), "sin identidad (rutas públicas) no se cuenta aquí");
    }
}
