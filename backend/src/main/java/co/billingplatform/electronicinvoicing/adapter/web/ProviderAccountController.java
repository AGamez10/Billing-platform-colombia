package co.billingplatform.electronicinvoicing.adapter.web;

import co.billingplatform.electronicinvoicing.application.ProviderAccountService;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@RestController
@RequestMapping("/api/v1/provider-accounts")
@Tag(name = "Proveedor tecnológico")
@SecurityRequirement(name = "bearer")
class ProviderAccountController {

    record ActivateRequest(@NotBlank @Size(max = 32) String providerId, @NotNull ProviderEnvironment environment) {}

    record AccountView(UUID id, String providerId, String environment, boolean active, boolean simulated) {
        static AccountView of(ProviderAccount a, boolean active) {
            return new AccountView(a.id(), a.providerId().value(), a.environment().name(), active,
                    a.environment() == ProviderEnvironment.SIMULATION);
        }
    }

    private final ProviderAccountService accounts;
    private final TenantTransactions tx;

    ProviderAccountController(ProviderAccountService accounts, TenantTransactions tx) {
        this.accounts = accounts;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Activar proveedor y ambiente para nuevas facturas. Disponible hoy: providerId=mock, "
            + "environment=SIMULATION (sin efecto tributario).")
    AccountView activate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ActivateRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PROVIDER_MANAGE);
        return tx.inTenant(u.tenantId(), () ->
                AccountView.of(accounts.activate(u.tenantId(), u.actor(), r.providerId(), r.environment()), true));
    }

    @GetMapping
    @Operation(summary = "Cuentas de proveedor de la organización (sin credenciales)")
    List<AccountView> list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> accounts.list(u.tenantId()).stream()
                .map(s -> AccountView.of(s.account(), s.active())).toList());
    }
}
