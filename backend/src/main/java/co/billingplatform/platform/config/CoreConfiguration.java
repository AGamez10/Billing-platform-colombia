package co.billingplatform.platform.config;

import co.billingplatform.identity.application.AuthenticationService;
import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.identity.application.RefreshTokenService;
import co.billingplatform.identity.application.RegistrationService;
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
    RegistrationService registrationService(IdentityPorts.OrganizationOnboarding onboarding,
                                            IdentityPorts.TrialProvisioning trials, IdentityPorts.PasswordHasher hasher) {
        return new RegistrationService(onboarding, trials, hasher);
    }
}
