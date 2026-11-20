package co.billingplatform.identity.adapter.persistence;

import co.billingplatform.identity.application.TwoFactorPorts;
import co.billingplatform.identity.application.TwoFactorPorts.Challenge;
import co.billingplatform.identity.application.TwoFactorPorts.State;
import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Verificación en dos pasos (V21). */
@Repository
class JdbcTwoFactorStore implements TwoFactorPorts.Store, TwoFactorPorts.Challenges {

    private final JdbcClient jdbc;

    JdbcTwoFactorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<State> lock(UUID userId) {
        return jdbc.sql("""
                        SELECT user_id, secret_sealed, enabled_at, last_used_step, failed_count, locked_until
                        FROM user_two_factor WHERE user_id = :u FOR UPDATE
                        """)
                .param("u", userId)
                .query((rs, i) -> new State(rs.getObject("user_id", UUID.class), rs.getBytes("secret_sealed"),
                        instant(rs.getTimestamp("enabled_at")), rs.getLong("last_used_step"), rs.getInt("failed_count"),
                        instant(rs.getTimestamp("locked_until"))))
                .optional();
    }

    @Override
    public boolean isEnabled(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_two_factor WHERE user_id = :u AND enabled_at IS NOT NULL)")
                .param("u", userId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public void savePending(UUID userId, byte[] sealedSecret, Instant at) {
        jdbc.sql("""
                        INSERT INTO user_two_factor (user_id, secret_sealed, created_at) VALUES (:u, :s, :at)
                        ON CONFLICT (user_id) DO UPDATE
                            SET secret_sealed = EXCLUDED.secret_sealed, created_at = EXCLUDED.created_at,
                                last_used_step = 0, failed_count = 0, locked_until = NULL
                            WHERE user_two_factor.enabled_at IS NULL
                        """)
                .param("u", userId)
                .param("s", sealedSecret)
                .param("at", Timestamp.from(at))
                .update();
    }

    @Override
    public void enable(UUID userId, long step, Instant at) {
        jdbc.sql("""
                        UPDATE user_two_factor SET enabled_at = :at, last_used_step = :step, failed_count = 0, locked_until = NULL
                        WHERE user_id = :u AND enabled_at IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("step", step)
                .param("u", userId)
                .update();
    }

    @Override
    public void recordSuccess(UUID userId, long lastUsedStep) {
        jdbc.sql("""
                        UPDATE user_two_factor SET last_used_step = GREATEST(last_used_step, :step), failed_count = 0,
                            locked_until = NULL
                        WHERE user_id = :u
                        """)
                .param("step", lastUsedStep)
                .param("u", userId)
                .update();
    }

    @Override
    public void recordFailure(UUID userId, int failedCount, Instant lockedUntil) {
        jdbc.sql("UPDATE user_two_factor SET failed_count = :c, locked_until = :l WHERE user_id = :u")
                .param("c", failedCount)
                .param("l", lockedUntil == null ? null : Timestamp.from(lockedUntil))
                .param("u", userId)
                .update();
    }

    @Override
    public void delete(UUID userId) {
        jdbc.sql("DELETE FROM user_recovery_codes WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM user_two_factor WHERE user_id = :u").param("u", userId).update();
    }

    @Override
    public void replaceRecoveryCodes(UUID userId, List<byte[]> hashes, Instant at) {
        jdbc.sql("DELETE FROM user_recovery_codes WHERE user_id = :u").param("u", userId).update();
        for (byte[] hash : hashes) {
            jdbc.sql("INSERT INTO user_recovery_codes (user_id, code_hash, created_at) VALUES (:u, :h, :at)")
                    .param("u", userId)
                    .param("h", hash)
                    .param("at", Timestamp.from(at))
                    .update();
        }
    }

    @Override
    public boolean consumeRecoveryCode(UUID userId, byte[] hash, Instant at) {
        return jdbc.sql("""
                        UPDATE user_recovery_codes SET used_at = :at
                        WHERE user_id = :u AND code_hash = :h AND used_at IS NULL
                        """)
                .param("at", Timestamp.from(at))
                .param("u", userId)
                .param("h", hash)
                .update() == 1;
    }

    @Override
    public int recoveryCodesLeft(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = :u AND used_at IS NULL")
                .param("u", userId)
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------ retos de inicio de sesión

    @Override
    public void insert(byte[] tokenHash, UUID userId, TenantId tenantId, Instant expiresAt, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO two_factor_challenges (token_hash, user_id, organization_id, expires_at, created_at)
                        VALUES (:h, :u, :o, :exp, :at)
                        """)
                .param("h", tokenHash)
                .param("u", userId)
                .param("o", tenantId.value())
                .param("exp", Timestamp.from(expiresAt))
                .param("at", Timestamp.from(createdAt))
                .update();
    }

    @Override
    public Optional<Challenge> lock(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT user_id, organization_id, attempts, expires_at FROM two_factor_challenges
                        WHERE token_hash = :h FOR UPDATE
                        """)
                .param("h", tokenHash)
                .query((rs, i) -> new Challenge(rs.getObject("user_id", UUID.class),
                        new TenantId(rs.getObject("organization_id", UUID.class)), rs.getInt("attempts"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public void recordAttempt(byte[] tokenHash) {
        jdbc.sql("UPDATE two_factor_challenges SET attempts = attempts + 1 WHERE token_hash = :h")
                .param("h", tokenHash)
                .update();
    }

    @Override
    public void delete(byte[] tokenHash) {
        jdbc.sql("DELETE FROM two_factor_challenges WHERE token_hash = :h").param("h", tokenHash).update();
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.sql("DELETE FROM two_factor_challenges WHERE expires_at <= :now").param("now", Timestamp.from(now)).update();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
