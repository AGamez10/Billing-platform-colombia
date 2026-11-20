package co.billingplatform.identity.adapter.web;

import co.billingplatform.identity.application.TwoFactorService;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Verificación en dos pasos de la cuenta del usuario que está en sesión (ADR-0034). Es de la
 * persona, no de la organización: vale en todas sus organizaciones. Una clave de API no puede
 * usar estas rutas.
 */
@RestController
@RequestMapping("/api/v1/account/two-factor")
@Tag(name = "Cuenta")
@SecurityRequirement(name = "bearer")
class TwoFactorController {

    record StatusView(boolean enabled, int recoveryCodesLeft) {}

    record SetupView(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "SetupView[***]";
        }
    }

    record CodeRequest(@NotBlank @Size(max = 20) String code) {
        @Override
        public String toString() {
            return "CodeRequest[***]";
        }
    }

    record DisableRequest(@NotBlank @Size(max = 128) String password, @NotBlank @Size(max = 20) String code) {
        @Override
        public String toString() {
            return "DisableRequest[***]";
        }
    }

    record RecoveryCodesView(List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "RecoveryCodesView[***]";
        }
    }

    private final TwoFactorService twoFactor;
    private final TenantTransactions tx;

    TwoFactorController(TwoFactorService twoFactor, TenantTransactions tx) {
        this.twoFactor = twoFactor;
        this.tx = tx;
    }

    @GetMapping
    @Operation(summary = "¿Está activa? y cuántos códigos de recuperación quedan")
    StatusView status(@AuthenticationPrincipal Jwt jwt) {
        UUID user = user(jwt);
        TwoFactorService.Status s = tx.withoutTenant(() -> twoFactor.status(user));
        return new StatusView(s.enabled(), s.recoveryCodesLeft());
    }

    @PostMapping("/setup")
    @Operation(summary = "Generar el secreto para escanear con la app de autenticación (aún no queda activa)")
    SetupView setup(@AuthenticationPrincipal Jwt jwt) {
        UUID user = user(jwt);
        TwoFactorService.Setup s = tx.withoutTenant(() -> twoFactor.beginSetup(user));
        return new SetupView(s.secret(), s.otpauthUri());
    }

    @PostMapping("/enable")
    @Operation(summary = "Activar confirmando un código de la app. Devuelve los códigos de recuperación (una sola vez).")
    RecoveryCodesView enable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r) {
        UUID user = user(jwt);
        return new RecoveryCodesView(tx.withoutTenant(() -> twoFactor.enable(user, r.code())));
    }

    @PostMapping("/recovery-codes")
    @Operation(summary = "Generar códigos de recuperación nuevos (invalida los anteriores)")
    RecoveryCodesView regenerate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r) {
        UUID user = user(jwt);
        return new RecoveryCodesView(tx.withoutTenantKeepingWritesOnDomainError(
                () -> twoFactor.regenerateRecoveryCodes(user, r.code())));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Desactivar con la contraseña y un código")
    void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DisableRequest r) {
        UUID user = user(jwt);
        tx.withoutTenantKeepingWritesOnDomainError(() -> {
            twoFactor.disable(user, r.password(), r.code());
            return null;
        });
    }

    private static UUID user(Jwt jwt) {
        return CurrentUser.from(jwt).requireUser().userId();
    }
}
