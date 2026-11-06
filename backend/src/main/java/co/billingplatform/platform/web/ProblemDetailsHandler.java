package co.billingplatform.platform.web;

import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;
import co.billingplatform.shared.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.UUID;

/**
 * Traduce errores a RFC 9457 ({@code application/problem+json}) con un {@code code} estable.
 * Nunca devuelve trazas ni mensajes internos de errores inesperados. Los errores estándar de
 * Spring MVC (validación, JSON mal formado, método no soportado, 404) los resuelve la clase base.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            Map.entry("INVOICE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("PROVIDER_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("NUMBERING_RANGE_NOT_FOUND", HttpStatus.CONFLICT),
            Map.entry("NO_PROVIDER_CONFIGURED", HttpStatus.CONFLICT),
            Map.entry("INVALID_STATE_TRANSITION", HttpStatus.CONFLICT),
            Map.entry("RECONCILIATION_REQUIRED", HttpStatus.CONFLICT),
            Map.entry("CONFLICTING_PROVIDER_RESULT", HttpStatus.CONFLICT),
            Map.entry("NUMBERING_RANGE_EXHAUSTED", HttpStatus.CONFLICT),
            Map.entry("NUMBERING_RESOLUTION_NOT_VALID", HttpStatus.CONFLICT),
            Map.entry("NUMBERING_ENVIRONMENT_MISMATCH", HttpStatus.CONFLICT),
            Map.entry("SUBSCRIPTION_REQUIRED", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("SUBSCRIPTION_INACTIVE", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("DOCUMENT_QUOTA_EXCEEDED", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED),
            Map.entry("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED),
            Map.entry("REFRESH_TOKEN_REUSED", HttpStatus.UNAUTHORIZED),
            Map.entry("INVALID_API_KEY", HttpStatus.UNAUTHORIZED),
            Map.entry("TENANT_ACCESS_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("PROVIDER_NOT_AVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("EMAIL_ALREADY_REGISTERED", HttpStatus.CONFLICT),
            Map.entry("ORGANIZATION_ALREADY_REGISTERED", HttpStatus.CONFLICT),
            Map.entry("CLIENT_HEADER_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("DOCUMENT_QUOTA_REACHED", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("TRIAL_QUOTA_EXHAUSTED", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED),
            Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("CUSTOMER_ALREADY_EXISTS", HttpStatus.CONFLICT),
            Map.entry("PRODUCT_SKU_ALREADY_EXISTS", HttpStatus.CONFLICT),
            Map.entry("PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("OVERAGE_NOT_AVAILABLE", HttpStatus.CONFLICT));

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_CONTENT);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(WebhookVerificationException.class)
    ProblemDetail webhook(WebhookVerificationException e) {
        log.warn("Rejected webhook: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setProperty("code", "WEBHOOK_REJECTED");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        String incident = UUID.randomUUID().toString();
        log.error("Unexpected error, incident {}", incident, e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error. Reference: " + incident);
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }
}
