package co.billingplatform.platform.security;

import co.billingplatform.platform.web.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de peticiones por identidad autenticada: por clave de API y por persona, en ventanas de
 * un minuto. Acota el daño de una clave o un token filtrados y el de una integración con un
 * bucle. En memoria, como {@link AuthRateLimitFilter}: suficiente para una sola instancia
 * (ADR-0001).
 *
 * <p>Va después de la autenticación: una petición sin credenciales válidas ya se rechazó, y las
 * rutas públicas no tienen identidad que contar.
 */
public class PrincipalRateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, int count) {}

    private static final int MAX_TRACKED = 100_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int apiKeyPerMinute;
    private final int userPerMinute;
    private final Clock clock;

    public PrincipalRateLimitFilter(int apiKeyPerMinute, int userPerMinute, Clock clock) {
        this.apiKeyPerMinute = apiKeyPerMinute;
        this.userPerMinute = userPerMinute;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean apiKey = Boolean.TRUE.equals(jwt.getClaim(CurrentUser.API_KEY_CLAIM));
        String key = (apiKey ? "k:" : "u:") + jwt.getSubject();
        int limit = apiKey ? apiKeyPerMinute : userPerMinute;
        long minute = clock.millis() / 60_000;
        if (windows.size() > MAX_TRACKED) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (w.count() > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(60 - (clock.millis() / 1000) % 60));
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"status":429,"title":"Too Many Requests","code":"RATE_LIMITED","detail":"Too many requests for this credential, slow down"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
