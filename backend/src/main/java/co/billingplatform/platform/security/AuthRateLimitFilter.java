package co.billingplatform.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de peticiones por IP para registro, login y renovación de sesión (fuerza bruta,
 * abuso de registro). Ventana fija de un minuto, en memoria: suficiente para una sola
 * instancia (ADR-0001). Complementa el bloqueo progresivo por cuenta.
 *
 * <p>Detrás de un proxy, la IP real exige {@code server.forward-headers-strategy=native} y un
 * proxy confiable; nunca se lee {@code X-Forwarded-For} directamente.
 *
 * <p>No es un bean: se instancia dentro de la cadena de seguridad para que no se registre
 * además como filtro global del servlet.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, int count) {}

    private static final int MAX_TRACKED_IPS = 100_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int perMinute;
    private final Clock clock;

    public AuthRateLimitFilter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !("POST".equals(request.getMethod()) && (uri.equals("/api/v1/auth/register") || uri.equals("/api/v1/auth/login")
                || uri.equals("/api/v1/auth/refresh") || uri.equals("/api/v1/auth/logout")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = clock.millis() / 60_000;
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        Window w = windows.compute(request.getRemoteAddr(), (ip, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (w.count() > perMinute) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"status":429,"title":"Too Many Requests","code":"RATE_LIMITED","detail":"Too many attempts, try again in a minute"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
