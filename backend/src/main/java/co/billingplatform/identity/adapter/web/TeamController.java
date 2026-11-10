package co.billingplatform.identity.adapter.web;

import co.billingplatform.identity.application.AccountPorts;
import co.billingplatform.identity.application.TeamService;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/team")
@Tag(name = "Equipo")
@SecurityRequirement(name = "bearer")
class TeamController {

    record InviteRequest(@NotBlank @Size(max = 254) String email, @NotNull Role role) {}

    record RoleRequest(@NotNull Role role) {}

    record MemberView(UUID userId, String fullName, String email, String role, Instant since, boolean you) {}

    record InvitationView(UUID id, String email, String role, Instant sentAt, Instant expiresAt) {}

    record TeamView(List<MemberView> members, List<InvitationView> invitations, Integer maxUsers) {}

    private final TeamService team;
    private final TenantTransactions tx;

    TeamController(TeamService team, TenantTransactions tx) {
        this.team = team;
        this.tx = tx;
    }

    @GetMapping
    @Operation(summary = "Miembros e invitaciones abiertas. Solo dueño o administrador.")
    TeamView list(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USERS_MANAGE);
        TeamService.Overview o = tx.inTenant(u.tenantId(), () -> team.overview(u.tenantId()));
        return new TeamView(
                o.members().stream().map(m -> new MemberView(m.userId(), m.fullName(), m.email(), m.role().name(), m.since(),
                        m.userId().equals(u.userId()))).toList(),
                o.invitations().stream().map(TeamController::view).toList(),
                o.maxUsers());
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Invitar por correo con un rol (ADMIN, BILLER o VIEWER). Reenviar reemplaza la invitación anterior.")
    InvitationView invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USERS_MANAGE);
        return view(tx.inTenant(u.tenantId(), () -> team.invite(acting(u), r.email(), r.role())));
    }

    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Anular una invitación abierta.")
    void revokeInvitation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USERS_MANAGE);
        tx.inTenant(u.tenantId(), () -> {
            team.revokeInvitation(acting(u), id);
            return null;
        });
    }

    @PatchMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cambiar el rol de un miembro (nunca el del dueño ni el propio).")
    void changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId, @Valid @RequestBody RoleRequest r) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USERS_MANAGE);
        tx.inTenant(u.tenantId(), () -> {
            team.changeRole(acting(u), userId, r.role());
            return null;
        });
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirar el acceso de un miembro. Sus sesiones dejan de renovarse.")
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        CurrentUser u = CurrentUser.from(jwt).require(Permission.USERS_MANAGE);
        tx.inTenant(u.tenantId(), () -> {
            team.remove(acting(u), userId);
            return null;
        });
    }

    private static TeamService.Acting acting(CurrentUser u) {
        return new TeamService.Acting(u.tenantId(), u.userId(), u.role());
    }

    private static InvitationView view(AccountPorts.Invitation i) {
        return new InvitationView(i.id(), i.email(), i.role().name(), i.createdAt(), i.expiresAt());
    }
}
