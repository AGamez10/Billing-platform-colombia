package co.billingplatform.identity.application;

import co.billingplatform.identity.application.AccountPorts.Invitation;
import co.billingplatform.identity.application.AccountPorts.Member;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.identity.domain.PasswordPolicy;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * El equipo de una organización: invitar por correo con un rol, aceptar la invitación (cuenta
 * nueva o existente), cambiar roles y retirar acceso.
 *
 * <p>Reglas: nadie asigna un rol con más permisos que el suyo ({@link Role#canGrant}); el dueño
 * no se invita, no se degrada ni se retira; nadie se cambia ni se retira a sí mismo; el enlace
 * se liga al correo invitado y vence a los 7 días.
 */
public final class TeamService {

    public static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

    public record Overview(List<Member> members, List<Invitation> invitations, Integer maxUsers) {}

    public record Preview(String organizationName, String email, Role role, boolean existingAccount) {}

    public record Accepted(UUID userId, TenantId tenantId, Role role, String organizationName) {}

    /** Quien actúa: su id y su rol en la organización en contexto. */
    public record Acting(TenantId tenantId, UUID userId, Role role) {}

    private final AccountPorts.Team team;
    private final AccountPorts.Invitations invitations;
    private final AccountPorts.SeatLimit seats;
    private final AccountPorts.Accounts accounts;
    private final IdentityPorts.UserAccounts users;
    private final IdentityPorts.Memberships memberships;
    private final IdentityPorts.PasswordHasher hasher;
    private final IdentityPorts.LegalAcceptances acceptances;
    private final IdentityPorts.LegalVersions legal;
    private final Mailer mailer;
    private final IdentityEmails emails;
    private final AuditTrail audit;
    private final Clock clock;

    public TeamService(AccountPorts.Team team, AccountPorts.Invitations invitations, AccountPorts.SeatLimit seats,
                       AccountPorts.Accounts accounts, IdentityPorts.UserAccounts users,
                       IdentityPorts.Memberships memberships, IdentityPorts.PasswordHasher hasher,
                       IdentityPorts.LegalAcceptances acceptances, IdentityPorts.LegalVersions legal, Mailer mailer,
                       IdentityEmails emails, AuditTrail audit, Clock clock) {
        this.team = Objects.requireNonNull(team);
        this.invitations = Objects.requireNonNull(invitations);
        this.seats = Objects.requireNonNull(seats);
        this.accounts = Objects.requireNonNull(accounts);
        this.users = Objects.requireNonNull(users);
        this.memberships = Objects.requireNonNull(memberships);
        this.hasher = Objects.requireNonNull(hasher);
        this.acceptances = Objects.requireNonNull(acceptances);
        this.legal = Objects.requireNonNull(legal);
        this.mailer = Objects.requireNonNull(mailer);
        this.emails = Objects.requireNonNull(emails);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Overview overview(TenantId tenantId) {
        return new Overview(team.members(tenantId), invitations.open(tenantId, clock.instant()),
                seats.maxUsers(tenantId).orElse(null));
    }

    // ------------------------------------------------------------------ invitar

    public Invitation invite(Acting acting, String email, Role role) {
        requireCanGrant(acting.role(), role);
        if (role == Role.OWNER) {
            throw new DomainException("FORBIDDEN_ROLE", "The owner cannot be invited");
        }
        String normalized = normalizeEmail(email);
        Instant now = clock.instant();
        Optional<IdentityPorts.UserAccount> existing = users.findByEmail(normalized);
        if (existing.isPresent() && team.member(acting.tenantId(), existing.get().id()).filter(Member::active).isPresent()) {
            throw new DomainException("ALREADY_MEMBER", "That person is already part of the team");
        }
        // Reenviar a un correo con invitación abierta la reemplaza (no ocupa otro cupo).
        invitations.revokeOpenFor(acting.tenantId(), normalized, now);
        Optional<Integer> max = seats.maxUsers(acting.tenantId());
        if (max.isPresent() && team.activeCount(acting.tenantId()) + invitations.openCount(acting.tenantId(), now) >= max.get()) {
            throw new DomainException("SEAT_LIMIT_REACHED", "The plan allows up to " + max.get() + " users");
        }
        String token = OpaqueTokens.randomBase64Url(32);
        UUID id = UUID.randomUUID();
        Instant expires = now.plus(INVITATION_VALIDITY);
        invitations.insert(acting.tenantId(), id, normalized, role, OpaqueTokens.sha256(token), acting.userId(), expires, now);
        String organization = organizationName(acting);
        mailer.send(emails.invitation(normalized, token, organization, accounts.fullName(acting.userId()).orElse(null), role,
                (int) INVITATION_VALIDITY.toDays()));
        audit.record(new AuditTrail.Event(acting.tenantId(), Actor.user(acting.userId()), "INVITATION_SENT", "invitation",
                id.toString(), Map.of("role", role.name())));
        return new Invitation(acting.tenantId(), id, normalized, role, organization, expires, null, null, now);
    }

    public void revokeInvitation(Acting acting, UUID invitationId) {
        requireManager(acting.role());
        if (!invitations.revoke(acting.tenantId(), invitationId, clock.instant())) {
            throw new DomainException("INVITATION_NOT_FOUND", "Invitation not found or no longer open");
        }
        audit.record(new AuditTrail.Event(acting.tenantId(), Actor.user(acting.userId()), "INVITATION_REVOKED",
                "invitation", invitationId.toString(), Map.of()));
    }

    // ------------------------------------------------------------------ aceptar (sin sesión)

    /** Sin tenant en contexto: qué organización invita, a qué correo y con qué rol. */
    public Preview preview(String token) {
        Invitation invitation = openInvitation(token);
        return new Preview(invitation.organizationName(), invitation.email(), invitation.role(),
                users.findByEmail(invitation.email()).isPresent());
    }

    /** Organización de la invitación, para abrir la transacción en su contexto. */
    public TenantId tenantOf(String token) {
        return openInvitation(token).tenantId();
    }

    /**
     * Cuenta existente, dentro del tenant de la invitación. Quien llama ya verificó la contraseña
     * con {@link AuthenticationService} (con su bloqueo por intentos fallidos); aquí se exige que
     * esa cuenta sea la del correo invitado.
     */
    public Accepted acceptExisting(TenantId tenantId, String token, UUID verifiedUserId) {
        Invitation invitation = openInvitation(token, tenantId);
        UUID owner = users.findByEmail(invitation.email()).map(IdentityPorts.UserAccount::id).orElse(null);
        if (!Objects.equals(owner, verifiedUserId)) {
            throw invalidInvitation();
        }
        return complete(invitation, verifiedUserId, false);
    }

    /** Cuenta nueva: nombre, contraseña y aceptación de los textos legales vigentes. */
    public Accepted acceptNew(TenantId tenantId, String token, String fullName, String password, String termsVersion,
                              String privacyVersion) {
        Invitation invitation = openInvitation(token, tenantId);
        if (users.findByEmail(invitation.email()).isPresent()) {
            throw new DomainException("ACCOUNT_EXISTS_SIGN_IN", "This email already has an account: use its password");
        }
        if (!legal.terms().equals(termsVersion) || !legal.privacy().equals(privacyVersion)) {
            throw new DomainException("LEGAL_ACCEPTANCE_REQUIRED",
                    "You must accept the current terms (%s) and privacy policy (%s)".formatted(legal.terms(), legal.privacy()));
        }
        if (fullName == null || fullName.isBlank() || fullName.length() > 200) {
            throw new DomainException("INVALID_NAME", "Full name is required (max 200 characters)");
        }
        PasswordPolicy.validate(password, invitation.email());
        UUID userId = UUID.randomUUID();
        accounts.create(userId, invitation.email(), hasher.hash(password), fullName.strip());
        acceptances.record(tenantId, userId, "TERMS", legal.terms());
        acceptances.record(tenantId, userId, "PRIVACY", legal.privacy());
        return complete(invitation, userId, true);
    }

    private Accepted complete(Invitation invitation, UUID userId, boolean newAccount) {
        TenantId tenantId = invitation.tenantId();
        if (!invitations.markAccepted(tenantId, invitation.id(), userId, clock.instant())) {
            throw invalidInvitation();
        }
        Optional<Member> current = team.member(tenantId, userId).filter(Member::active);
        Role role = invitation.role();
        if (current.isPresent() && current.get().role() == Role.OWNER) {
            role = Role.OWNER; // una invitación nunca rebaja al dueño
        } else {
            team.activate(tenantId, userId, role);
        }
        audit.record(new AuditTrail.Event(tenantId, Actor.user(userId), "INVITATION_ACCEPTED", "invitation",
                invitation.id().toString(), Map.of("role", role.name(), "newAccount", String.valueOf(newAccount))));
        return new Accepted(userId, tenantId, role, invitation.organizationName());
    }

    // ------------------------------------------------------------------ miembros

    public void changeRole(Acting acting, UUID userId, Role newRole) {
        Member member = modifiable(acting, userId);
        requireCanGrant(acting.role(), newRole);
        if (newRole == Role.OWNER) {
            throw new DomainException("FORBIDDEN_ROLE", "Ownership cannot be assigned here");
        }
        team.changeRole(acting.tenantId(), userId, newRole);
        audit.record(new AuditTrail.Event(acting.tenantId(), Actor.user(acting.userId()), "MEMBER_ROLE_CHANGED", "user",
                userId.toString(), Map.of("from", member.role().name(), "to", newRole.name())));
    }

    /** Retira el acceso. Sus sesiones dejan de renovarse; el token vigente vence en minutos. */
    public void remove(Acting acting, UUID userId) {
        Member member = modifiable(acting, userId);
        team.revoke(acting.tenantId(), userId);
        audit.record(new AuditTrail.Event(acting.tenantId(), Actor.user(acting.userId()), "MEMBER_REMOVED", "user",
                userId.toString(), Map.of("role", member.role().name())));
    }

    private Member modifiable(Acting acting, UUID userId) {
        requireManager(acting.role());
        if (acting.userId().equals(userId)) {
            throw new DomainException("CANNOT_MODIFY_SELF", "You cannot change your own access");
        }
        Member member = team.member(acting.tenantId(), userId).filter(Member::active)
                .orElseThrow(() -> new DomainException("MEMBER_NOT_FOUND", "Member not found"));
        if (member.role() == Role.OWNER) {
            throw new DomainException("CANNOT_MODIFY_OWNER", "The owner's access cannot be changed");
        }
        requireCanGrant(acting.role(), member.role());
        return member;
    }

    // ------------------------------------------------------------------ utilidades

    private Invitation openInvitation(String token, TenantId expectedTenant) {
        Invitation invitation = openInvitation(token);
        if (!invitation.tenantId().equals(expectedTenant)) {
            throw invalidInvitation();
        }
        return invitation;
    }

    private Invitation openInvitation(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,64}")) {
            throw invalidInvitation();
        }
        return invitations.findByHash(OpaqueTokens.sha256(token))
                .filter(i -> i.isOpen(clock.instant()))
                .orElseThrow(TeamService::invalidInvitation);
    }

    private String organizationName(Acting acting) {
        return memberships.activeFor(acting.userId()).stream()
                .filter(m -> m.tenantId().equals(acting.tenantId()))
                .map(IdentityPorts.Membership::organizationName)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("tu organización");
    }

    private static void requireManager(Role role) {
        if (!role.can(co.billingplatform.identity.domain.Permission.USERS_MANAGE)) {
            throw new DomainException("FORBIDDEN", "Your role does not allow this operation");
        }
    }

    private static void requireCanGrant(Role actor, Role target) {
        requireManager(actor);
        if (!actor.canGrant(target)) {
            throw new DomainException("FORBIDDEN_ROLE", "You cannot assign a role with more permissions than yours");
        }
    }

    private static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || normalized.length() > 254) {
            throw new DomainException("INVALID_EMAIL", "Email is not valid");
        }
        return normalized;
    }

    private static DomainException invalidInvitation() {
        return new DomainException("INVITATION_INVALID", "This invitation is invalid, expired or was already used");
    }
}
