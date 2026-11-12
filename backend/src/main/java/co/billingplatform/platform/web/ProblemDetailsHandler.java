package co.billingplatform.platform.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
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
            Map.entry("OVERAGE_NOT_AVAILABLE", HttpStatus.CONFLICT),
            Map.entry("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("IDEMPOTENCY_KEY_IN_PROGRESS", HttpStatus.CONFLICT),
            Map.entry("PAYMENT_ACCOUNT_REQUIRED", HttpStatus.CONFLICT),
            Map.entry("PAYMENT_ENVIRONMENT_MISMATCH", HttpStatus.CONFLICT),
            Map.entry("PAYMENT_GATEWAY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("CHARGE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("PLAN_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("PLAN_ALREADY_ACTIVE", HttpStatus.CONFLICT),
            Map.entry("INVALID_RESET_LINK", HttpStatus.GONE),
            Map.entry("SUBSCRIPTION_PAYMENTS_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("SUBSCRIPTION_PAYMENTS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("CHECKOUT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("CHECKOUT_ALREADY_PAID", HttpStatus.CONFLICT),
            Map.entry("CHECKOUT_EXPIRED", HttpStatus.CONFLICT),
            Map.entry("INVITATION_INVALID", HttpStatus.GONE),
            Map.entry("INVITATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("MEMBER_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("FORBIDDEN_ROLE", HttpStatus.FORBIDDEN),
            Map.entry("ALREADY_MEMBER", HttpStatus.CONFLICT),
            Map.entry("SEAT_LIMIT_REACHED", HttpStatus.CONFLICT),
            Map.entry("CANNOT_MODIFY_SELF", HttpStatus.CONFLICT),
            Map.entry("CANNOT_MODIFY_OWNER", HttpStatus.CONFLICT),
            Map.entry("ACCOUNT_EXISTS_SIGN_IN", HttpStatus.CONFLICT),
            Map.entry("WEBHOOK_SIGNATURE_INVALID", HttpStatus.UNAUTHORIZED));

    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_CONTENT);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", e.code());
        return problem;
    }

    /**
     * Validación de forma: además del detalle estándar, devuelve {@code code} y la lista de
     * campos inválidos (sin eco de los valores enviados) para que la interfaz marque cada campo.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fields", ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(WebhookVerificationException.class)
    ProblemDetail webhook(WebhookVerificationException e) {
        log.warn("Rejected webhook: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setProperty("code", "WEBHOOK_REJECTED");
        return problem;
    }

    /** Un registro que otro usa (p. ej. borrar el borrador de una venta con cobro): conflicto, sin detalles SQL. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail integrity(org.springframework.dao.DataIntegrityViolationException e) {
        log.info("Integrity conflict: {}", e.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource is in use or conflicts with another one.");
        problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem.setProperty("code", "RESOURCE_IN_USE");
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
