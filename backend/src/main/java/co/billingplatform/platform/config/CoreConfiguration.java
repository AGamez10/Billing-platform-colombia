package co.billingplatform.platform.config;

import co.billingplatform.identity.application.AccountPorts;
import co.billingplatform.identity.application.AuthenticationService;
import co.billingplatform.identity.application.IdentityEmails;
import co.billingplatform.identity.application.PasswordRecoveryService;
import co.billingplatform.identity.application.OrganizationProfileService;
import co.billingplatform.identity.application.TeamService;
import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.identity.application.RefreshTokenService;
import co.billingplatform.identity.application.RegistrationService;
import co.billingplatform.customers.application.CustomerRepository;
import co.billingplatform.customers.application.CustomerService;
import co.billingplatform.products.application.ProductRepository;
import co.billingplatform.products.application.ProductService;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.Municipalities;
import co.billingplatform.platform.catalog.DivipolaCatalog;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.tax.domain.TaxCatalog;
import org.springframework.beans.factory.annotation.Value;
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
    Municipalities municipalities() {
        return DivipolaCatalog.fromClasspath();
    }

    @Bean
    CustomerService customerService(CustomerRepository customers, AuditTrail audit, Municipalities municipalities) {
        return new CustomerService(customers, audit, municipalities);
    }

    @Bean
    ProductService productService(ProductRepository products, TaxCatalog taxes, AuditTrail audit, Clock clock) {
        return new ProductService(products, taxes, audit, clock);
    }

    @Bean
    RegistrationService registrationService(IdentityPorts.OrganizationOnboarding onboarding,
                                            IdentityPorts.TrialProvisioning trials, IdentityPorts.PasswordHasher hasher,
                                            IdentityPorts.LegalAcceptances acceptances, IdentityPorts.LegalVersions legal) {
        return new RegistrationService(onboarding, trials, hasher, acceptances, legal);
    }

    @Bean
    IdentityEmails identityEmails(@Value("${billing.brand.name}") String brand, @Value("${billing.web.base-url}") String baseUrl) {
        return new IdentityEmails(brand, baseUrl);
    }

    @Bean
    PasswordRecoveryService passwordRecoveryService(IdentityPorts.UserAccounts users, AccountPorts.PasswordResets resets,
                                                    AccountPorts.Accounts accounts, IdentityPorts.PasswordHasher hasher,
                                                    Mailer mailer, IdentityEmails emails, Clock clock) {
        return new PasswordRecoveryService(users, resets, accounts, hasher, mailer, emails, clock);
    }

    @Bean
    TeamService teamService(AccountPorts.Team team, AccountPorts.Invitations invitations, AccountPorts.SeatLimit seats,
                            AccountPorts.Accounts accounts, IdentityPorts.UserAccounts users,
                            IdentityPorts.Memberships memberships, IdentityPorts.PasswordHasher hasher,
                            IdentityPorts.LegalAcceptances acceptances, IdentityPorts.LegalVersions legal, Mailer mailer,
                            IdentityEmails emails, AuditTrail audit, Clock clock) {
        return new TeamService(team, invitations, seats, accounts, users, memberships, hasher, acceptances, legal, mailer,
                emails, audit, clock);
    }

    @Bean
    OrganizationProfileService organizationProfileService(OrganizationProfileService.Profiles profiles,
                                                          Municipalities municipalities, AuditTrail audit) {
        return new OrganizationProfileService(profiles, municipalities, audit);
    }

    /** Versiones vigentes de términos y política; cambiarlas obliga a aceptar de nuevo al registrarse. */
    @Bean
    IdentityPorts.LegalVersions legalVersions(@Value("${billing.legal.terms-version}") String terms,
                                              @Value("${billing.legal.privacy-version}") String privacy) {
        return new IdentityPorts.LegalVersions(terms, privacy);
    }
}
