package co.billingplatform.identity.adapter.web;

import co.billingplatform.identity.application.AuthenticationService;
import co.billingplatform.identity.application.IdentityPorts.Membership;
import co.billingplatform.identity.application.IdentityPorts.Memberships;
import co.billingplatform.identity.application.RefreshTokenService;
import co.billingplatform.identity.application.PasswordRecoveryService;
import co.billingplatform.identity.application.RegistrationService;
import co.billingplatform.identity.application.TeamService;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.security.AccessTokens;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registro, inicio de sesión y renovación de sesión.
 *
 * <p>El access token (15 min) va en el cuerpo y el frontend lo guarda solo en memoria. El
 * refresh token va en una cookie {@code HttpOnly; Secure; SameSite=Strict} limitada a
 * {@code /api/v1/auth}. Renovar exige además la cabecera {@code X-Billing-Client}, que un
 * formulario de otro sitio no puede enviar sin CORS (defensa CSRF).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación")
class AuthController {

    static final String REFRESH_COOKIE = "bp_refresh";
    static final String CLIENT_HEADER = "X-Billing-Client";

    record RegisterRequest(
            @NotBlank @Size(max = 450) String organizationName,
            @NotNull IdentificationType identificationType,
            @NotBlank @Size(max = 20) String identificationNumber,
            Integer checkDigit,
            @Size(max = 254) String organizationEmail,
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 40) String acceptedTermsVersion,
            @NotBlank @Size(max = 40) String acceptedPrivacyVersion) {
        @Override
        public String toString() {
            return "RegisterRequest[organizationName=%s, email=%s]".formatted(organizationName, email);
        }
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password,
                        UUID organizationId) {
        @Override
        public String toString() {
            return "LoginRequest[email=%s]".formatted(email);
        }
    }

    record OrganizationView(UUID id, String name, String role) {}

    /** {@code status}: SIGNED_IN, o ORGANIZATION_REQUIRED si el usuario pertenece a varias y no eligió. */
    record SessionResponse(String status, String accessToken, String tokenType, Long expiresIn,
                           OrganizationView organization, List<OrganizationView> organizations) {}

    /** {@code organizations}: todas las del usuario, para cambiar de organización sin volver a escribir la clave. */
    record MeResponse(UUID userId, UUID organizationId, String organizationName, String role,
                      List<OrganizationView> organizations) {}

    record ResetRequest(@NotBlank @Size(max = 254) String email) {}

    record ResetConfirm(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "ResetConfirm[***]";
        }
    }

    record InvitationToken(@NotBlank @Size(max = 100) String token) {}

    record InvitationPreview(String organizationName, String email, String role, boolean existingAccount) {}

    record AcceptInvitation(@NotBlank @Size(max = 100) String token, @Size(max = 200) String fullName,
                            @NotBlank @Size(max = 128) String password,
                            @Size(max = 40) String acceptedTermsVersion, @Size(max = 40) String acceptedPrivacyVersion) {
        @Override
        public String toString() {
            return "AcceptInvitation[***]";
        }
    }

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final RefreshTokenService refreshTokens;
    private final AccessTokens accessTokens;
    private final TenantTransactions tx;
    private final Memberships memberships;
    private final PasswordRecoveryService recovery;
    private final TeamService team;

    AuthController(RegistrationService registration, AuthenticationService authentication,
                   RefreshTokenService refreshTokens, AccessTokens accessTokens, TenantTransactions tx,
                   Memberships memberships, PasswordRecoveryService recovery, TeamService team) {
        this.memberships = memberships;
        this.recovery = recovery;
        this.team = team;
        this.registration = registration;
        this.authentication = authentication;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.tx = tx;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una organización y su usuario dueño (inicia la prueba gratis de 20 documentos). "
            + "Exige aceptar las versiones vigentes de términos y política (GET /api/v1/public/legal).")
    SessionResponse register(@Valid @RequestBody RegisterRequest r, HttpServletResponse response) {
        RegistrationService.Registered registered = tx.withoutTenant(() -> registration.register(new RegistrationService.Command(
                r.organizationName(), r.identificationType(), r.identificationNumber(), r.checkDigit(),
                r.organizationEmail(), r.fullName(), r.email(), r.password(),
                r.acceptedTermsVersion(), r.acceptedPrivacyVersion())));
        Membership owner = new Membership(registered.tenantId(), co.billingplatform.identity.domain.Role.OWNER,
                r.organizationName().strip());
        return startSession(registered.ownerUserId(), owner, response);
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión con correo y contraseña")
    SessionResponse login(@Valid @RequestBody LoginRequest r, HttpServletResponse response) {
        // Confirma el contador de intentos fallidos aunque el login falle (si no, el bloqueo nunca operaría).
        AuthenticationService.AuthenticatedUser user = tx.withoutTenantKeepingWritesOnDomainError(
                () -> authentication.authenticate(r.email(), r.password()));
        if (user.memberships().isEmpty()) {
            throw new DomainException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        Membership chosen;
        if (r.organizationId() != null) {
            chosen = authentication.selectTenant(user, new TenantId(r.organizationId()));
        } else if (user.memberships().size() == 1) {
            chosen = user.memberships().getFirst();
        } else {
            return new SessionResponse("ORGANIZATION_REQUIRED", null, null, null, null,
                    user.memberships().stream().map(AuthController::view).toList());
        }
        return startSession(user.userId(), chosen, response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Renovar la sesión con la cookie de refresh (rota el token)")
    SessionResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                            @RequestHeader(name = CLIENT_HEADER, required = false) String client,
                            HttpServletResponse response) {
        requireClientHeader(client);
        if (refreshToken == null) {
            throw new DomainException("INVALID_REFRESH_TOKEN", "Session expired, please sign in again");
        }
        RefreshTokenService.Issued rotated;
        try {
            // Confirma la revocación de la familia aunque se rechace un token reutilizado.
            rotated = tx.withoutTenantKeepingWritesOnDomainError(() -> refreshTokens.rotate(refreshToken));
        } catch (DomainException e) {
            clearCookie(response);
            throw e;
        }
        setCookie(response, rotated.token(), rotated.expiresAt());
        AccessTokens.Issued access = accessTokens.issue(rotated.userId(), rotated.tenantId(), rotated.role());
        return new SessionResponse("SIGNED_IN", access.token(), "Bearer", AccessTokens.TTL.toSeconds(),
                new OrganizationView(rotated.tenantId().value(), null, rotated.role().name()), null);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cerrar sesión (revoca la cookie de refresh)")
    void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                @RequestHeader(name = CLIENT_HEADER, required = false) String client,
                HttpServletResponse response) {
        requireClientHeader(client);
        if (refreshToken != null) {
            tx.withoutTenant(() -> {
                refreshTokens.revoke(refreshToken);
                return null;
            });
        }
        clearCookie(response);
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Pedir el enlace para cambiar la contraseña. Responde igual exista o no la cuenta.")
    void requestPasswordReset(@Valid @RequestBody ResetRequest r) {
        tx.withoutTenant(() -> {
            recovery.request(r.email());
            return null;
        });
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elegir la nueva contraseña con el enlace del correo. Cierra todas las sesiones.")
    void confirmPasswordReset(@Valid @RequestBody ResetConfirm r) {
        tx.withoutTenant(() -> {
            recovery.confirm(r.token(), r.password());
            return null;
        });
    }

    @PostMapping("/invitations/preview")
    @Operation(summary = "Ver a qué organización y con qué rol invita un enlace (sin sesión).")
    InvitationPreview previewInvitation(@Valid @RequestBody InvitationToken r) {
        TeamService.Preview p = tx.withoutTenant(() -> team.preview(r.token()));
        return new InvitationPreview(p.organizationName(), p.email(), p.role().name(), p.existingAccount());
    }

    @PostMapping("/invitations/accept")
    @Operation(summary = "Aceptar una invitación e iniciar sesión en esa organización. Cuenta nueva: nombre, "
            + "contraseña y aceptación de los textos legales vigentes. Cuenta existente: su contraseña.")
    SessionResponse acceptInvitation(@Valid @RequestBody AcceptInvitation r, HttpServletResponse response) {
        TeamService.Preview preview = tx.withoutTenant(() -> team.preview(r.token()));
        TenantId tenant = tx.withoutTenant(() -> team.tenantOf(r.token()));
        TeamService.Accepted accepted;
        if (preview.existingAccount()) {
            // Misma verificación que el inicio de sesión, con su bloqueo por intentos fallidos.
            UUID userId = tx.withoutTenantKeepingWritesOnDomainError(
                    () -> authentication.authenticate(preview.email(), r.password())).userId();
            accepted = tx.inTenant(tenant, () -> team.acceptExisting(tenant, r.token(), userId));
        } else {
            accepted = tx.inTenant(tenant, () -> team.acceptNew(tenant, r.token(), r.fullName(), r.password(),
                    r.acceptedTermsVersion(), r.acceptedPrivacyVersion()));
        }
        return startSession(accepted.userId(), new Membership(accepted.tenantId(), accepted.role(),
                accepted.organizationName()), response);
    }

    @GetMapping("/me")
    @Operation(summary = "Sesión actual", security = @SecurityRequirement(name = "bearer"))
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID tenant = UUID.fromString(jwt.getClaimAsString("tid"));
        List<Membership> all = tx.withoutTenant(() -> memberships.activeFor(userId));
        String name = all.stream().filter(m -> m.tenantId().value().equals(tenant))
                .map(Membership::organizationName).findFirst().orElse(null);
        return new MeResponse(userId, tenant, name, jwt.getClaimAsString("role"),
                all.stream().map(AuthController::view).toList());
    }

    private SessionResponse startSession(UUID userId, Membership membership, HttpServletResponse response) {
        RefreshTokenService.Issued refresh = tx.asUser(userId, () -> refreshTokens.issue(userId, membership.tenantId()));
        setCookie(response, refresh.token(), refresh.expiresAt());
        AccessTokens.Issued access = accessTokens.issue(userId, membership.tenantId(), refresh.role());
        return new SessionResponse("SIGNED_IN", access.token(), "Bearer", AccessTokens.TTL.toSeconds(),
                view(membership), null);
    }

    private static OrganizationView view(Membership m) {
        return new OrganizationView(m.tenantId().value(), m.organizationName(), m.role().name());
    }

    private static void requireClientHeader(String client) {
        if (client == null || client.isBlank()) {
            throw new DomainException("CLIENT_HEADER_REQUIRED", "Missing " + CLIENT_HEADER + " header");
        }
    }

    private static void setCookie(HttpServletResponse response, String token, Instant expiresAt) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth")
                .maxAge(Duration.between(Instant.now(), expiresAt)).build().toString());
    }

    private static void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth").maxAge(0).build().toString());
    }
}
