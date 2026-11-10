package co.billingplatform.identity.application;

import co.billingplatform.identity.application.AccountPorts.Invitation;
import co.billingplatform.identity.application.AccountPorts.Member;
import co.billingplatform.identity.application.IdentityPorts.Membership;
import co.billingplatform.identity.application.IdentityPorts.UserAccount;
import co.billingplatform.identity.domain.OpaqueTokens;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Recuperar contraseña e invitar al equipo, con dobles en memoria. */
class AccountServicesTest {

    private Instant now = Instant.parse("2026-11-10T15:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID owner = UUID.randomUUID();
    private final Map<String, UserAccount> users = new HashMap<>();
    private final Map<UUID, String> hashes = new HashMap<>();
    private final List<Mailer.Email> outbox = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private final List<UUID> sessionsRevoked = new ArrayList<>();

    private final IdentityPorts.PasswordHasher hasher = new IdentityPorts.PasswordHasher() {
        @Override public String hash(String raw) { return "h:" + raw; }
        @Override public boolean matches(String raw, String encoded) { return encoded.equals("h:" + raw); }
        @Override public String dummyHash() { return "h:x"; }
    };

    private final IdentityPorts.UserAccounts userAccounts = new IdentityPorts.UserAccounts() {
        @Override public Optional<UserAccount> findByEmail(String e) { return Optional.ofNullable(users.get(e)); }
        @Override public void recordFailedLogin(UUID id, int c, Instant l) {}
        @Override public void recordSuccessfulLogin(UUID id) {}
    };

    // ---------------------------------------------------------------- recuperar contraseña
    private record Reset(UUID id, UUID userId, byte[] hash, Instant expiresAt, Instant createdAt, Instant usedAt) {}
    private final List<Reset> resets = new ArrayList<>();

    private final AccountPorts.PasswordResets resetStore = new AccountPorts.PasswordResets() {
        @Override public void invalidateOpen(UUID u, Instant at) {
            resets.replaceAll(r -> r.userId().equals(u) && r.usedAt() == null
                    ? new Reset(r.id(), r.userId(), r.hash(), r.expiresAt(), r.createdAt(), at) : r);
        }
        @Override public void insert(UUID id, UUID u, byte[] h, Instant exp, Instant at) { resets.add(new Reset(id, u, h, exp, at, null)); }
        @Override public Optional<AccountPorts.ResetToken> lockByHash(byte[] h) {
            return resets.stream().filter(r -> Arrays.equals(r.hash(), h)).findFirst()
                    .map(r -> new AccountPorts.ResetToken(r.id(), r.userId(), r.expiresAt(), r.usedAt()));
        }
        @Override public void markUsed(UUID id, Instant at) {
            resets.replaceAll(r -> r.id().equals(id) ? new Reset(r.id(), r.userId(), r.hash(), r.expiresAt(), r.createdAt(), at) : r);
        }
        @Override public int countSince(UUID u, Instant since) {
            return (int) resets.stream().filter(r -> r.userId().equals(u) && !r.createdAt().isBefore(since)).count();
        }
    };

    // ---------------------------------------------------------------- equipo
    private final Map<UUID, Member> members = new HashMap<>();
    private final List<Invitation> invitations = new ArrayList<>();
    private final Map<UUID, byte[]> invitationHashes = new HashMap<>();
    private Integer maxUsers;

    private final AccountPorts.Accounts accounts = new AccountPorts.Accounts() {
        @Override public Optional<String> activeEmail(UUID id) {
            return users.values().stream().filter(u -> u.id().equals(id) && u.active()).map(UserAccount::email).findFirst();
        }
        @Override public void changePassword(UUID id, String h) { hashes.put(id, h); }
        @Override public void revokeAllSessions(UUID id, Instant at) { sessionsRevoked.add(id); }
        @Override public void create(UUID id, String email, String h, String name) {
            users.put(email, new UserAccount(id, email, h, true, 0, null));
        }
        @Override public Optional<String> fullName(UUID id) { return Optional.of("Ana Dueña"); }
    };

    private final AccountPorts.Team team = new AccountPorts.Team() {
        @Override public List<Member> members(TenantId t) { return members.values().stream().filter(Member::active).toList(); }
        @Override public Optional<Member> member(TenantId t, UUID u) { return Optional.ofNullable(members.get(u)); }
        @Override public long activeCount(TenantId t) { return members.values().stream().filter(Member::active).count(); }
        @Override public void activate(TenantId t, UUID u, Role r) { members.put(u, new Member(u, null, null, r, true, now)); }
        @Override public void changeRole(TenantId t, UUID u, Role r) { Member m = members.get(u); members.put(u, new Member(u, null, null, r, true, m.since())); }
        @Override public void revoke(TenantId t, UUID u) { Member m = members.get(u); members.put(u, new Member(u, null, null, m.role(), false, m.since())); }
    };

    private final AccountPorts.Invitations invitationStore = new AccountPorts.Invitations() {
        @Override public void revokeOpenFor(TenantId t, String e, Instant at) {
            invitations.replaceAll(i -> i.email().equals(e) && i.acceptedAt() == null && i.revokedAt() == null
                    ? new Invitation(i.tenantId(), i.id(), i.email(), i.role(), i.organizationName(), i.expiresAt(), null, at, i.createdAt()) : i);
        }
        @Override public void insert(TenantId t, UUID id, String e, Role r, byte[] h, UUID by, Instant exp, Instant at) {
            invitations.add(new Invitation(t, id, e, r, "Fundación Huellas", exp, null, null, at));
            invitationHashes.put(id, h);
        }
        @Override public Optional<Invitation> findByHash(byte[] h) {
            return invitations.stream().filter(i -> Arrays.equals(invitationHashes.get(i.id()), h)).findFirst();
        }
        @Override public List<Invitation> open(TenantId t, Instant n) { return invitations.stream().filter(i -> i.isOpen(n)).toList(); }
        @Override public long openCount(TenantId t, Instant n) { return open(t, n).size(); }
        @Override public boolean revoke(TenantId t, UUID id, Instant at) {
            for (int k = 0; k < invitations.size(); k++) {
                Invitation i = invitations.get(k);
                if (i.id().equals(id) && i.acceptedAt() == null && i.revokedAt() == null) {
                    invitations.set(k, new Invitation(i.tenantId(), i.id(), i.email(), i.role(), i.organizationName(), i.expiresAt(), null, at, i.createdAt()));
                    return true;
                }
            }
            return false;
        }
        @Override public boolean markAccepted(TenantId t, UUID id, UUID u, Instant at) {
            for (int k = 0; k < invitations.size(); k++) {
                Invitation i = invitations.get(k);
                if (i.id().equals(id) && i.isOpen(at)) {
                    invitations.set(k, new Invitation(i.tenantId(), i.id(), i.email(), i.role(), i.organizationName(), i.expiresAt(), at, null, i.createdAt()));
                    return true;
                }
            }
            return false;
        }
    };

    private final IdentityEmails emails = new IdentityEmails("Billing Platform", "https://app.ejemplo.co/");
    private final IdentityPorts.LegalVersions legal = new IdentityPorts.LegalVersions("t1", "p1");
    private final PasswordRecoveryService recovery = new PasswordRecoveryService(userAccounts, resetStore, accounts, hasher,
            outbox::add, emails, clock);
    private final TeamService teamService = new TeamService(team, invitationStore, t -> Optional.ofNullable(maxUsers), accounts,
            userAccounts, u -> List.of(new Membership(tenant, Role.OWNER, "Fundación <Huellas>")), hasher,
            (t, u, d, v) -> audit.add("LEGAL_" + d), legal, outbox::add, emails, (AuditTrail.Event e) -> audit.add(e.action()), clock);

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        users.put(email, new UserAccount(id, email, "h:clave anterior 2026", true, 0, null));
        return id;
    }

    /** El token viaja solo en el enlace del correo, después del '#t='. */
    private static String tokenFrom(Mailer.Email email) {
        String text = email.text();
        int at = text.indexOf("#t=") + 3;
        int end = at;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '-' || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(at, end);
    }

    // ---------------------------------------------------------------- pruebas de recuperación

    @Test
    @DisplayName("recuperar: un enlace de un solo uso que cambia la clave, cierra sesiones y avisa")
    void resetHappyPath() {
        UUID ana = user("ana@huellas.org");
        recovery.request("  ANA@huellas.org ");
        assertEquals(1, outbox.size());
        Mailer.Email mail = outbox.getFirst();
        assertTrue(mail.text().contains("https://app.ejemplo.co/restablecer#t="), "token en el fragmento, nunca en la ruta");
        assertFalse(mail.toString().contains("#t="), "el cuerpo nunca se imprime en registros");
        String token = tokenFrom(mail);
        assertFalse(resets.stream().anyMatch(r -> new String(r.hash()).contains(token)), "solo se guarda el hash");

        recovery.confirm(token, "una clave nueva y larga");
        assertEquals("h:una clave nueva y larga", hashes.get(ana));
        assertEquals(List.of(ana), sessionsRevoked);
        assertTrue(outbox.getLast().subject().contains("cambió"));

        DomainException again = assertThrows(DomainException.class, () -> recovery.confirm(token, "otra clave nueva larga"));
        assertEquals("INVALID_RESET_LINK", again.code(), "el enlace sirve una sola vez");
    }

    @Test
    @DisplayName("recuperar: no revela cuentas, vence a los 30 min, solo vale el último y limita a 5 por hora")
    void resetRules() {
        recovery.request("nadie@ningun-lado.co");
        assertTrue(outbox.isEmpty(), "sin cuenta, nada se envía y nada falla");

        user("ana@huellas.org");
        recovery.request("ana@huellas.org");
        String first = tokenFrom(outbox.getLast());
        recovery.request("ana@huellas.org");
        String second = tokenFrom(outbox.getLast());
        assertEquals("INVALID_RESET_LINK", assertThrows(DomainException.class,
                () -> recovery.confirm(first, "una clave nueva y larga")).code(), "pedir otro enlace anula el anterior");

        now = now.plus(Duration.ofMinutes(31));
        assertEquals("INVALID_RESET_LINK", assertThrows(DomainException.class,
                () -> recovery.confirm(second, "una clave nueva y larga")).code(), "vence a los 30 minutos");

        for (int i = 0; i < 6; i++) {
            recovery.request("ana@huellas.org");
        }
        assertEquals(5, outbox.size(), "máximo 5 enlaces por hora por cuenta (2 de antes + 3)");

        String last = tokenFrom(outbox.getLast());
        assertEquals("WEAK_PASSWORD", assertThrows(DomainException.class, () -> recovery.confirm(last, "corta")).code());
        assertEquals("INVALID_RESET_LINK", assertThrows(DomainException.class, () -> recovery.confirm("../../etc", "x")).code());
    }

    // ---------------------------------------------------------------- pruebas de equipo

    private TeamService.Acting asOwner() {
        members.putIfAbsent(owner, new Member(owner, "ana@huellas.org", "Ana", Role.OWNER, true, now));
        users.putIfAbsent("ana@huellas.org", new UserAccount(owner, "ana@huellas.org", "h:clave de la dueña", true, 0, null));
        return new TeamService.Acting(tenant, owner, Role.OWNER);
    }

    @Test
    @DisplayName("invitar: correo con enlace al rol; cuenta nueva acepta con nombre, clave y textos legales")
    void inviteNewAccount() {
        teamService.invite(asOwner(), " Caja@Huellas.org ", Role.BILLER);
        Mailer.Email mail = outbox.getLast();
        assertEquals("caja@huellas.org", mail.to());
        assertTrue(mail.html().contains("Fundación &lt;Huellas&gt;"), "el nombre de la organización se escapa en el HTML");
        assertTrue(mail.text().contains("como facturador"));
        String token = tokenFrom(mail);

        TeamService.Preview preview = teamService.preview(token);
        assertEquals(Role.BILLER, preview.role());
        assertFalse(preview.existingAccount());

        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", assertThrows(DomainException.class,
                () -> teamService.acceptNew(tenant, token, "Carlos Caja", "una clave nueva y larga", "viejo", "p1")).code());
        TeamService.Accepted accepted = teamService.acceptNew(tenant, token, "Carlos Caja", "una clave nueva y larga", "t1", "p1");
        assertEquals(Role.BILLER, accepted.role());
        assertTrue(members.get(accepted.userId()).active());
        assertTrue(audit.containsAll(List.of("LEGAL_TERMS", "LEGAL_PRIVACY", "INVITATION_SENT", "INVITATION_ACCEPTED")));
        assertEquals("INVITATION_INVALID", assertThrows(DomainException.class, () -> teamService.preview(token)).code(),
                "una invitación aceptada no se reutiliza");
    }

    @Test
    @DisplayName("invitar: cuenta existente solo con la cuenta verificada del correo invitado; otra organización no sirve")
    void inviteExistingAccount() {
        UUID luis = user("luis@contador.co");
        UUID intruso = user("intruso@x.co");
        teamService.invite(asOwner(), "luis@contador.co", Role.VIEWER);
        String token = tokenFrom(outbox.getLast());
        assertTrue(teamService.preview(token).existingAccount());
        assertEquals("ACCOUNT_EXISTS_SIGN_IN", assertThrows(DomainException.class,
                () -> teamService.acceptNew(tenant, token, "Luis", "una clave nueva y larga", "t1", "p1")).code());
        assertEquals("INVITATION_INVALID", assertThrows(DomainException.class,
                () -> teamService.acceptExisting(tenant, token, intruso)).code(), "otra cuenta no puede usar el enlace");
        assertEquals("INVITATION_INVALID", assertThrows(DomainException.class,
                () -> teamService.acceptExisting(new TenantId(UUID.randomUUID()), token, luis)).code());
        assertEquals(Role.VIEWER, teamService.acceptExisting(tenant, token, luis).role());
    }

    @Test
    @DisplayName("invitar: sin escalar privilegios, sin duplicar miembros, reenviar reemplaza y respeta el límite del plan")
    void inviteRules() {
        TeamService.Acting admin = new TeamService.Acting(tenant, UUID.randomUUID(), Role.ADMIN);
        TeamService.Acting biller = new TeamService.Acting(tenant, UUID.randomUUID(), Role.BILLER);
        assertEquals("FORBIDDEN", assertThrows(DomainException.class,
                () -> teamService.invite(biller, "a@b.co", Role.VIEWER)).code());
        assertEquals("FORBIDDEN_ROLE", assertThrows(DomainException.class,
                () -> teamService.invite(admin, "a@b.co", Role.OWNER)).code());
        assertEquals("INVALID_EMAIL", assertThrows(DomainException.class,
                () -> teamService.invite(asOwner(), "no-es-correo", Role.VIEWER)).code());
        assertEquals("ALREADY_MEMBER", assertThrows(DomainException.class,
                () -> teamService.invite(asOwner(), "ana@huellas.org", Role.VIEWER)).code());

        teamService.invite(asOwner(), "caja@huellas.org", Role.BILLER);
        String old = tokenFrom(outbox.getLast());
        teamService.invite(asOwner(), "caja@huellas.org", Role.VIEWER);
        assertEquals(1, teamService.overview(tenant).invitations().size(), "reenviar reemplaza la anterior");
        assertEquals("INVITATION_INVALID", assertThrows(DomainException.class, () -> teamService.preview(old)).code());

        maxUsers = 2; // dueño + 1 invitación abierta
        assertEquals("SEAT_LIMIT_REACHED", assertThrows(DomainException.class,
                () -> teamService.invite(asOwner(), "otro@huellas.org", Role.VIEWER)).code());
        assertDoesNotThrow(() -> teamService.invite(asOwner(), "caja@huellas.org", Role.BILLER),
                "reenviar al mismo correo no ocupa otro cupo");

        now = now.plus(TeamService.INVITATION_VALIDITY).plusSeconds(1);
        assertEquals("INVITATION_INVALID", assertThrows(DomainException.class,
                () -> teamService.preview(tokenFrom(outbox.getLast()))).code(), "vence a los 7 días");
    }

    @Test
    @DisplayName("miembros: el dueño y uno mismo no se tocan; un administrador no degrada a otro rol superior al suyo")
    void memberRules() {
        TeamService.Acting ownerActing = asOwner();
        UUID adminId = UUID.randomUUID();
        UUID billerId = UUID.randomUUID();
        members.put(adminId, new Member(adminId, "admin@h.org", "Admin", Role.ADMIN, true, now));
        members.put(billerId, new Member(billerId, "caja@h.org", "Caja", Role.BILLER, true, now));
        TeamService.Acting admin = new TeamService.Acting(tenant, adminId, Role.ADMIN);

        assertEquals("CANNOT_MODIFY_OWNER", assertThrows(DomainException.class, () -> teamService.remove(admin, owner)).code());
        assertEquals("CANNOT_MODIFY_SELF", assertThrows(DomainException.class, () -> teamService.remove(admin, adminId)).code());
        assertEquals("FORBIDDEN_ROLE", assertThrows(DomainException.class,
                () -> teamService.changeRole(admin, billerId, Role.OWNER)).code());

        teamService.changeRole(admin, billerId, Role.VIEWER);
        assertEquals(Role.VIEWER, members.get(billerId).role());
        teamService.remove(ownerActing, adminId);
        assertFalse(members.get(adminId).active());
        assertEquals("MEMBER_NOT_FOUND", assertThrows(DomainException.class,
                () -> teamService.remove(ownerActing, adminId)).code());
        assertTrue(audit.containsAll(List.of("MEMBER_ROLE_CHANGED", "MEMBER_REMOVED")));
    }

    @Test
    @DisplayName("los tokens de los enlaces tienen 256 bits y el formato esperado")
    void tokenFormat() {
        String token = OpaqueTokens.randomBase64Url(32);
        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]{40,64}"));
    }
}
