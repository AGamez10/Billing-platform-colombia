package co.billingplatform.identity.adapter.persistence;

import co.billingplatform.identity.application.AccountPorts;
import co.billingplatform.identity.application.AccountPorts.Invitation;
import co.billingplatform.identity.application.AccountPorts.Member;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Recuperación de contraseña, cuentas y equipo (V14). Membresías e invitaciones bajo RLS del tenant. */
@Repository
class JdbcAccountStore implements AccountPorts.PasswordResets, AccountPorts.Accounts, AccountPorts.Team,
        AccountPorts.Invitations {

    private final JdbcClient jdbc;

    JdbcAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ recuperar contraseña

    @Override
    public void invalidateOpen(UUID userId, Instant at) {
        jdbc.sql("UPDATE password_reset_tokens SET used_at = :at WHERE user_id = :u AND used_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("u", userId)
                .update();
    }

    @Override
    public void insert(UUID id, UUID userId, byte[] tokenHash, Instant expiresAt, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, created_at)
                        VALUES (:id, :u, :h, :exp, :at)
                        """)
                .param("id", id)
                .param("u", userId)
                .param("h", tokenHash)
                .param("exp", Timestamp.from(expiresAt))
                .param("at", Timestamp.from(createdAt))
                .update();
    }

    @Override
    public Optional<AccountPorts.ResetToken> lockByHash(byte[] tokenHash) {
        return jdbc.sql("SELECT id, user_id, expires_at, used_at FROM password_reset_tokens WHERE token_hash = :h FOR UPDATE")
                .param("h", tokenHash)
                .query((rs, n) -> new AccountPorts.ResetToken(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), instant(rs, "expires_at"), instant(rs, "used_at")))
                .optional();
    }

    @Override
    public void markUsed(UUID id, Instant at) {
        jdbc.sql("UPDATE password_reset_tokens SET used_at = :at WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("id", id)
                .update();
    }

    @Override
    public int countSince(UUID userId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM password_reset_tokens WHERE user_id = :u AND created_at >= :since")
                .param("u", userId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------ cuentas

    @Override
    public Optional<String> activeEmail(UUID userId) {
        return jdbc.sql("SELECT email FROM users WHERE id = :id AND status = 'ACTIVE'")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<String> fullName(UUID userId) {
        return jdbc.sql("SELECT full_name FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public void changePassword(UUID userId, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :h, failed_login_count = 0, locked_until = NULL WHERE id = :id")
                .param("h", passwordHash)
                .param("id", userId)
                .update();
    }

    @Override
    public void revokeAllSessions(UUID userId, Instant at) {
        // refresh_tokens solo deja operar sobre los del usuario en contexto (RLS).
        jdbc.sql("SELECT set_config('app.user_id', :u, true)").param("u", userId.toString()).query(String.class).single();
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :at WHERE user_id = :u AND revoked_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("u", userId)
                .update();
    }

    @Override
    public void create(UUID userId, String email, String passwordHash, String fullName) {
        try {
            jdbc.sql("""
                            INSERT INTO users (id, email, password_hash, full_name, email_verified_at)
                            VALUES (:id, :email, :h, :name, now())
                            """)
                    .param("id", userId)
                    .param("email", email)
                    .param("h", passwordHash)
                    .param("name", fullName)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DomainException("EMAIL_ALREADY_REGISTERED", "Email already registered");
        }
    }

    // ------------------------------------------------------------------ equipo

    private static final String MEMBER_SELECT = """
            SELECT m.user_id, u.email, u.full_name, m.role, m.status, m.created_at
            FROM memberships m JOIN users u ON u.id = m.user_id
            WHERE m.tenant_id = :t""";

    @Override
    public List<Member> members(TenantId tenantId) {
        return jdbc.sql(MEMBER_SELECT + " AND m.status = 'ACTIVE' ORDER BY (m.role = 'OWNER') DESC, u.full_name")
                .param("t", tenantId.value())
                .query(JdbcAccountStore::member)
                .list();
    }

    @Override
    public Optional<Member> member(TenantId tenantId, UUID userId) {
        return jdbc.sql(MEMBER_SELECT + " AND m.user_id = :u")
                .param("t", tenantId.value())
                .param("u", userId)
                .query(JdbcAccountStore::member)
                .optional();
    }

    @Override
    public long activeCount(TenantId tenantId) {
        return jdbc.sql("SELECT count(*) FROM memberships WHERE tenant_id = :t AND status = 'ACTIVE'")
                .param("t", tenantId.value())
                .query(Long.class)
                .single();
    }

    @Override
    public void activate(TenantId tenantId, UUID userId, Role role) {
        jdbc.sql("""
                        INSERT INTO memberships (tenant_id, user_id, role, status) VALUES (:t, :u, :r, 'ACTIVE')
                        ON CONFLICT (tenant_id, user_id) DO UPDATE SET role = EXCLUDED.role, status = 'ACTIVE'
                        """)
                .param("t", tenantId.value())
                .param("u", userId)
                .param("r", role.name())
                .update();
    }

    @Override
    public void changeRole(TenantId tenantId, UUID userId, Role role) {
        jdbc.sql("UPDATE memberships SET role = :r WHERE tenant_id = :t AND user_id = :u AND status = 'ACTIVE' AND role <> 'OWNER'")
                .param("r", role.name())
                .param("t", tenantId.value())
                .param("u", userId)
                .update();
    }

    @Override
    public void revoke(TenantId tenantId, UUID userId) {
        jdbc.sql("UPDATE memberships SET status = 'REVOKED' WHERE tenant_id = :t AND user_id = :u AND role <> 'OWNER'")
                .param("t", tenantId.value())
                .param("u", userId)
                .update();
    }

    // ------------------------------------------------------------------ invitaciones

    @Override
    public void revokeOpenFor(TenantId tenantId, String email, Instant at) {
        jdbc.sql("""
                        UPDATE invitations SET revoked_at = :at
                        WHERE tenant_id = :t AND email = :e AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("t", tenantId.value())
                .param("e", email)
                .update();
    }

    @Override
    public void insert(TenantId tenantId, UUID id, String email, Role role, byte[] tokenHash, UUID invitedBy,
                       Instant expiresAt, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO invitations (tenant_id, id, email, role, token_hash, invited_by, expires_at, created_at)
                        VALUES (:t, :id, :e, :r, :h, :by, :exp, :at)
                        """)
                .param("t", tenantId.value())
                .param("id", id)
                .param("e", email)
                .param("r", role.name())
                .param("h", tokenHash)
                .param("by", invitedBy)
                .param("exp", Timestamp.from(expiresAt))
                .param("at", Timestamp.from(createdAt))
                .update();
    }

    @Override
    public Optional<Invitation> findByHash(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM invitation_find(:h)")
                .param("h", tokenHash)
                .query((rs, n) -> new Invitation(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("id", UUID.class), rs.getString("email"), Role.valueOf(rs.getString("role")),
                        rs.getString("organization_name"), instant(rs, "expires_at"), instant(rs, "accepted_at"),
                        instant(rs, "revoked_at"), null))
                .optional();
    }

    @Override
    public List<Invitation> open(TenantId tenantId, Instant now) {
        return jdbc.sql("""
                        SELECT id, email, role, expires_at, created_at FROM invitations
                        WHERE tenant_id = :t AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                        ORDER BY created_at DESC
                        """)
                .param("t", tenantId.value())
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Invitation(tenantId, rs.getObject("id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role")), null, instant(rs, "expires_at"), null, null,
                        instant(rs, "created_at")))
                .list();
    }

    @Override
    public long openCount(TenantId tenantId, Instant now) {
        return jdbc.sql("""
                        SELECT count(*) FROM invitations
                        WHERE tenant_id = :t AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now
                        """)
                .param("t", tenantId.value())
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    @Override
    public boolean revoke(TenantId tenantId, UUID id, Instant at) {
        return jdbc.sql("""
                        UPDATE invitations SET revoked_at = :at
                        WHERE tenant_id = :t AND id = :id AND accepted_at IS NULL AND revoked_at IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("t", tenantId.value())
                .param("id", id)
                .update() == 1;
    }

    @Override
    public boolean markAccepted(TenantId tenantId, UUID id, UUID userId, Instant at) {
        return jdbc.sql("""
                        UPDATE invitations SET accepted_at = :at, accepted_user_id = :u
                        WHERE tenant_id = :t AND id = :id AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :at
                        """)
                .param("at", Timestamp.from(at))
                .param("u", userId)
                .param("t", tenantId.value())
                .param("id", id)
                .update() == 1;
    }

    private static Member member(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                Role.valueOf(rs.getString("role")), "ACTIVE".equals(rs.getString("status")), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
