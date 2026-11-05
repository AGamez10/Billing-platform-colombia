package co.billingplatform.platform.config;

import co.billingplatform.identity.application.AuthenticationService;
import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.identity.application.RefreshTokenService;
import co.billingplatform.identity.application.RegistrationService;
import co.billingplatform.customers.application.CustomerRepository;
import co.billingplatform.customers.application.CustomerService;
import co.billingplatform.products.application.ProductRepository;
import co.billingplatform.products.application.ProductService;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.tax.domain.TaxCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Ensambla los casos de uso (sin frameworks) con sus adaptadores. */
@Configuration
class CoreConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AuthenticationService authenticationService(IdentityPorts.UserAccounts users, IdentityPorts.Memberships memberships,
                                                IdentityPorts.PasswordHasher hasher, Clock clock) {
        return new AuthenticationService(users, memberships, hasher, clock);
    }

    @Bean
    RefreshTokenService refreshTokenService(IdentityPorts.RefreshTokens tokens, IdentityPorts.Memberships memberships,
                                            Clock clock) {
        return new RefreshTokenService(tokens, memberships, clock);
    }

    @Bean
    CustomerService customerService(CustomerRepository customers, AuditTrail audit) {
        return new CustomerService(customers, audit);
    }

    @Bean
    ProductService productService(ProductRepository products, TaxCatalog taxes, AuditTrail audit, Clock clock) {
        return new ProductService(products, taxes, audit, clock);
    }

    @Bean
    RegistrationService registrationService(IdentityPorts.OrganizationOnboarding onboarding,
                                            IdentityPorts.TrialProvisioning trials, IdentityPorts.PasswordHasher hasher) {
        return new RegistrationService(onboarding, trials, hasher);
    }
}
