package co.billingplatform.identity.application;

import co.billingplatform.identity.application.IdentityPorts.LegalAcceptances;
import co.billingplatform.identity.application.IdentityPorts.LegalVersions;
import co.billingplatform.identity.application.IdentityPorts.OrganizationOnboarding;
import co.billingplatform.identity.application.IdentityPorts.PasswordHasher;
import co.billingplatform.identity.application.IdentityPorts.TrialProvisioning;
import co.billingplatform.identity.domain.PasswordPolicy;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Alta de una organización nueva con su usuario dueño. Todo en una transacción: organización,
 * usuario, membresía OWNER y suscripción de prueba gratis (20 documentos).
 */
public final class RegistrationService {

    public record Command(String organizationLegalName, IdentificationType identificationType,
                          String identificationNumber, Integer checkDigit, String organizationEmail,
                          String ownerFullName, String ownerEmail, String password,
                          String acceptedTermsVersion, String acceptedPrivacyVersion) {
        @Override
        public String toString() {
            return "Command[org=%s, ownerEmail=%s]".formatted(organizationLegalName, ownerEmail);
        }
    }

    public record Registered(TenantId tenantId, UUID ownerUserId) {}

    private final OrganizationOnboarding onboarding;
    private final TrialProvisioning trials;
    private final PasswordHasher hasher;
    private final LegalAcceptances acceptances;
    private final LegalVersions legal;

    public RegistrationService(OrganizationOnboarding onboarding, TrialProvisioning trials, PasswordHasher hasher,
                               LegalAcceptances acceptances, LegalVersions legal) {
        this.onboarding = Objects.requireNonNull(onboarding);
        this.trials = Objects.requireNonNull(trials);
        this.hasher = Objects.requireNonNull(hasher);
        this.acceptances = Objects.requireNonNull(acceptances);
        this.legal = Objects.requireNonNull(legal);
    }

    public Registered register(Command c) {
        // Se acepta exactamente la versión vigente: una pantalla vieja no registra una aceptación ambigua.
        if (!legal.terms().equals(c.acceptedTermsVersion()) || !legal.privacy().equals(c.acceptedPrivacyVersion())) {
            throw new DomainException("LEGAL_ACCEPTANCE_REQUIRED",
                    "You must accept the current terms (%s) and privacy policy (%s)".formatted(legal.terms(), legal.privacy()));
        }
        if (c.identificationType() == null) {
            throw new DomainException("INVALID_IDENTIFICATION", "Identification type is required");
        }
        PartyIdentification identification = new PartyIdentification(
                c.identificationType(), c.identificationNumber(), c.checkDigit());
        // Reutiliza las validaciones de parte (nombre, correo) del dominio compartido.
        Party organization = new Party(identification, c.organizationLegalName(), c.organizationEmail());

        String ownerEmail = c.ownerEmail() == null ? "" : c.ownerEmail().strip().toLowerCase(Locale.ROOT);
        if (!ownerEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || ownerEmail.length() > 254) {
            throw new DomainException("INVALID_EMAIL", "Owner email is not valid");
        }
        if (c.ownerFullName() == null || c.ownerFullName().isBlank() || c.ownerFullName().length() > 200) {
            throw new DomainException("INVALID_NAME", "Owner full name is required (max 200 characters)");
        }
        PasswordPolicy.validate(c.password(), ownerEmail);

        TenantId tenantId = new TenantId(UUID.randomUUID());
        UUID ownerId = UUID.randomUUID();
        onboarding.register(tenantId.value(), organization.legalName(), identification, organization.email(),
                ownerId, ownerEmail, hasher.hash(c.password()), c.ownerFullName().strip());
        trials.startTrial(tenantId);
        acceptances.record(tenantId, ownerId, "TERMS", legal.terms());
        acceptances.record(tenantId, ownerId, "PRIVACY", legal.privacy());
        return new Registered(tenantId, ownerId);
    }
}
