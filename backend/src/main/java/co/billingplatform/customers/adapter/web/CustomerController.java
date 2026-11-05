package co.billingplatform.customers.adapter.web;

import co.billingplatform.customers.application.CustomerService;
import co.billingplatform.customers.domain.Customer;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.platform.web.CurrentUser;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Clientes")
@SecurityRequirement(name = "bearer")
class CustomerController {

    record CustomerRequest(
            @NotNull IdentificationType identificationType,
            @NotBlank @Size(max = 20) String identificationNumber,
            Integer checkDigit,
            @NotBlank @Size(max = 450) String legalName,
            @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 300) String address,
            @Size(max = 10) String cityCode) {
        CustomerService.Details details() {
            return new CustomerService.Details(identificationType, identificationNumber, checkDigit, legalName,
                    email, phone, address, cityCode);
        }
    }

    record UpdateCustomerRequest(@NotNull @PositiveOrZero Integer version, @NotNull @Valid CustomerRequest customer) {}

    record CustomerView(UUID id, String identificationType, String identificationNumber, Integer checkDigit,
                        String legalName, String email, String phone, String address, String cityCode,
                        boolean active, int version) {
        static CustomerView of(Customer c) {
            return new CustomerView(c.id(), c.identification().type().name(), c.identification().number(),
                    c.identification().checkDigit(), c.legalName(), c.email(), c.phone(), c.address(), c.cityCode(),
                    c.active(), c.version());
        }
    }

    private final CustomerService customers;
    private final TenantTransactions tx;

    CustomerController(CustomerService customers, TenantTransactions tx) {
        this.customers = customers;
        this.tx = tx;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crear cliente (NIT con dígito de verificación validado)")
    CustomerView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CustomerRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_WRITE);
        return tx.inTenant(u.tenantId(), () -> CustomerView.of(customers.create(u.tenantId(), u.actor(), r.details())));
    }

    @GetMapping
    @Operation(summary = "Buscar clientes por nombre o número de identificación (paginado, máx. 100)")
    Page<CustomerView> search(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "false") boolean includeInactive,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(required = false) Integer offset) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_READ);
        return tx.inTenant(u.tenantId(), () -> {
            Page<Customer> page = customers.search(u.tenantId(), q, includeInactive, limit, offset);
            return new Page<>(page.items().stream().map(CustomerView::of).toList(), page.limit(), page.offset(), page.hasMore());
        });
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar cliente")
    CustomerView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_READ);
        return tx.inTenant(u.tenantId(), () -> CustomerView.of(customers.get(u.tenantId(), id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar cliente (envía la versión que leíste; si otro lo cambió responde 409)")
    CustomerView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_WRITE);
        return tx.inTenant(u.tenantId(), () ->
                CustomerView.of(customers.update(u.tenantId(), u.actor(), id, r.version(), r.customer().details())));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desactivar cliente (no se borra: las facturas lo referencian)")
    CustomerView deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.CUSTOMER_WRITE);
        return tx.inTenant(u.tenantId(), () -> CustomerView.of(customers.deactivate(u.tenantId(), u.actor(), id)));
    }
}
