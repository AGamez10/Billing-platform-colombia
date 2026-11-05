package co.billingplatform.identity.adapter.persistence;

import co.billingplatform.identity.application.IdentityPorts;
import co.billingplatform.identity.application.IdentityPorts.ApiKeyRecord;
import co.billingplatform.identity.application.IdentityPorts.Membership;
import co.billingplatform.identity.application.IdentityPorts.RefreshTokenRecord;
import co.billingplatform.identity.application.IdentityPorts.UserAccount;
import co.billingplatform.identity.domain.Permission;
import co.billingplatform.identity.domain.Role;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistencia de identidad con SQL explícito (ADR-0013). Las búsquedas previas a conocer
 * tenant o usuario usan las funciones SECURITY DEFINER acotadas (V1, V7); el resto opera
 * bajo RLS con el contexto que fija {@code TenantTransactions}.
 */
@Repository
class JdbcIdentityStore implements IdentityPorts.UserAccounts, IdentityPorts.Memberships,
        IdentityPorts.RefreshTokens, IdentityPorts.ApiKeys, IdentityPorts.OrganizationOnboarding {

    private final JdbcClient jdbc;

    JdbcIdentityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ usuarios

    @Override
    public Optional<UserAccount> findByEmail(String normalizedEmail) {
        return jdbc.sql("""
                        SELECT id, email, password_hash, status, failed_login_count, locked_until
                        FROM users WHERE email = :email
                        """)
                .param("email", normalizedEmail)
                .query((rs, i) -> new UserAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        "ACTIVE".equals(rs.getString("status")),
                        rs.getInt("failed_login_count"),
                        instant(rs.getTimestamp("locked_until"))))
                .optional();
    }

    @Override
    public void recordFailedLogin(UUID userId, int failedLoginCount, Instant lockedUntil) {
        jdbc.sql("UPDATE users SET failed_login_count = :c, locked_until = :l WHERE id = :id")
                .param("c", failedLoginCount)
                .param("l", lockedUntil == null ? null : Timestamp.from(lockedUntil))
                .param("id", userId)
                .update();
    }

    @Override
    public void recordSuccessfulLogin(UUID userId) {
        jdbc.sql("UPDATE users SET failed_login_count = 0, locked_until = NULL WHERE id = :id")
                .param("id", userId)
                .update();
    }

    @Override
    public List<Membership> activeFor(UUID userId) {
        return jdbc.sql("SELECT tenant_id, role, legal_name FROM auth_user_memberships(:u)")
                .param("u", userId)
                .query((rs, i) -> new Membership(
                        new TenantId(rs.getObject("tenant_id", UUID.class)),
                        Role.valueOf(rs.getString("role")),
                        rs.getString("legal_name")))
                .list();
    }

    // ------------------------------------------------------------------ registro

    @Override
    public void register(UUID organizationId, String legalName, PartyIdentification identification,
                         String organizationEmail, UUID ownerUserId, String ownerEmail, String passwordHash,
                         String ownerFullName) {
        try {
            jdbc.sql("""
                            SELECT register_organization(:org, :name, :idType, :idNumber, :dv, :orgEmail,
                                                         :user, :email, :hash, :fullName)
                            """)
                    .param("org", organizationId)
                    .param("name", legalName)
                    .param("idType", identification.type().name())
                    .param("idNumber", identification.number())
                    .param("dv", identification.checkDigit() == null ? null : identification.checkDigit().shortValue())
                    .param("orgEmail", organizationEmail)
                    .param("user", ownerUserId)
                    .param("email", ownerEmail)
                    .param("hash", passwordHash)
                    .param("fullName", ownerFullName)
                    .query((rs, i) -> 1)
                    .single();
        } catch (DuplicateKeyException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("users_email_uk")) {
                throw new DomainException("EMAIL_ALREADY_REGISTERED", "That email is already registered");
            }
            throw new DomainException("ORGANIZATION_ALREADY_REGISTERED", "That organization is already registered");
        }
    }

    // ------------------------------------------------------------------ refresh tokens

    /**
     * Resuelve el token por hash (función definer) y, si existe, deja la transacción operando
     * como su usuario ({@code app.user_id}) para que la rotación o revocación posterior pase RLS.
     */
    @Override
    public Optional<RefreshTokenRecord> findByHash(byte[] tokenHash) {
        Optional<RefreshTokenRecord> found = jdbc.sql("""
                        SELECT id, user_id, tenant_id, family_id, expires_at, revoked_at, replaced_by
                        FROM auth_find_refresh_token(:h)
                        """)
                .param("h", tokenHash)
                .query((rs, i) -> new RefreshTokenRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("family_id", UUID.class),
                        tokenHash,
                        instant(rs.getTimestamp("expires_at")),
                        instant(rs.getTimestamp("revoked_at")),
                        rs.getObject("replaced_by", UUID.class)))
                .optional();
        found.ifPresent(t -> setUser(t.userId()));
        return found;
    }

    @Override
    public void insert(RefreshTokenRecord r) {
        setUser(r.userId());
        jdbc.sql("""
                        INSERT INTO refresh_tokens (id, user_id, tenant_id, family_id, token_hash, expires_at)
                        VALUES (:id, :u, :t, :f, :h, :e)
                        """)
                .param("id", r.id())
                .param("u", r.userId())
                .param("t", r.tenantId().value())
                .param("f", r.familyId())
                .param("h", r.tokenHash())
                .param("e", Timestamp.from(r.expiresAt()))
                .update();
    }

    @Override
    public boolean markReplaced(UUID tokenId, UUID replacedBy, Instant at) {
        return jdbc.sql("""
                        UPDATE refresh_tokens SET replaced_by = :by
                        WHERE id = :id AND replaced_by IS NULL AND revoked_at IS NULL
                        """)
                .param("by", replacedBy)
                .param("id", tokenId)
                .update() == 1;
    }

    @Override
    public void revokeFamily(UUID familyId, Instant at) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :at WHERE family_id = :f AND revoked_at IS NULL")
                .param("at", Timestamp.from(at))
                .param("f", familyId)
                .update();
    }

    // ------------------------------------------------------------------ API keys

    @Override
    public Optional<ApiKeyRecord> findByPrefix(String prefix) {
        return jdbc.sql("SELECT tenant_id, id, key_hash, scopes, expires_at FROM auth_find_api_key(:p)")
                .param("p", prefix)
                .query((rs, i) -> {
                    String[] scopes = (String[]) rs.getArray("scopes").getArray();
                    Set<Permission> permissions = EnumSet.noneOf(Permission.class);
                    Arrays.stream(scopes).map(Permission::valueOf).forEach(permissions::add);
                    return new ApiKeyRecord(new TenantId(rs.getObject("tenant_id", UUID.class)),
                            rs.getObject("id", UUID.class), null, prefix, rs.getBytes("key_hash"),
                            Set.copyOf(permissions), instant(rs.getTimestamp("expires_at")));
                })
                .optional();
    }

    @Override
    public void insert(ApiKeyRecord r) {
        jdbc.sql("""
                        INSERT INTO api_keys (tenant_id, id, name, key_prefix, key_hash, scopes, expires_at)
                        VALUES (:t, :id, :name, :prefix, :hash, :scopes, :exp)
                        """)
                .param("t", r.tenantId().value())
                .param("id", r.id())
                .param("name", r.name())
                .param("prefix", r.prefix())
                .param("hash", r.keyHash())
                .param("scopes", r.scopes().stream().map(Enum::name).sorted().toArray(String[]::new))
                .param("exp", r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()))
                .update();
    }

    // ------------------------------------------------------------------ utilidades

    private void setUser(UUID userId) {
        jdbc.sql("SELECT set_config('app.user_id', :u, true)").param("u", userId.toString()).query(String.class).single();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
