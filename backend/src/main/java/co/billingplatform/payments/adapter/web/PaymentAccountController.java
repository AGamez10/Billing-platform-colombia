package co.billingplatform.payments.adapter.web;

import co.billingplatform.identity.domain.Permission;
import co.billingplatform.payments.application.PaymentAccountService;
import co.billingplatform.payments.domain.PaymentAccount;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import com.fasterxml.jackson.annotation.JsonInclude;
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
@RequestMapping("/api/v1/payment-accounts")
@Tag(name = "Cobros: cuenta de pasarela del comercio")
@SecurityRequirement(name = "bearer")
class PaymentAccountController {

    /** Las llaves nunca se devuelven ni se registran; se guardan cifradas. */
    record ConnectRequest(@NotNull PaymentAccount.Mode mode,
                          @NotBlank @Size(max = 200) String identityKey,
                          @Size(max = 200) String secretKey) {
        @Override
        public String toString() {
            return "ConnectRequest[mode=" + mode + ", keys=***]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AccountView(boolean connected, UUID id, String gateway, String mode, List<String> paymentMethods,
                       String webhookPath) {}

    private final PaymentAccountService accounts;
    private final TenantTransactions tx;

    PaymentAccountController(PaymentAccountService accounts, TenantTransactions tx) {
        this.accounts = accounts;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Conecta la cuenta Bold del comercio (llaves de PRUEBA o de PRODUCCIÓN). Verifica las llaves "
            + "antes de guardarlas y reemplaza la cuenta anterior. La ruta del webhook se muestra UNA sola vez.")
    AccountView connect(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConnectRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.PROVIDER_MANAGE);
        PaymentAccountService.Connected c = tx.inTenant(u.tenantId(),
                () -> accounts.connect(u.tenantId(), u.actor(), r.mode(), r.identityKey(), r.secretKey()));
        return new AccountView(true, c.account().id(), c.account().gateway().name(), c.account().mode().name(),
                c.paymentMethods(), "/api/v1/webhooks/bold/" + c.webhookToken());
    }

    @GetMapping
    @Operation(summary = "Cuenta de pasarela activa (sin llaves ni ruta del webhook).")
    AccountView current(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.INVOICE_READ);
        return tx.inTenant(u.tenantId(), () -> accounts.active(u.tenantId())
                .map(a -> new AccountView(true, a.id(), a.gateway().name(), a.mode().name(), null, null))
                .orElse(new AccountView(false, null, null, null, null, null)));
    }
}
