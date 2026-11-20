-- =====================================================================================
-- V21 — Verificación en dos pasos (TOTP, RFC 6238) y códigos de recuperación (ADR-0034)
-- Datos de la cuenta del usuario, no de una organización (como users y password_reset_tokens):
-- se consultan antes de conocer el tenant. El secreto va cifrado con AES-256-GCM ligado al
-- usuario (una copia en otra fila no se descifra) y los códigos de recuperación solo como hash.
-- =====================================================================================

CREATE TABLE user_two_factor (
    user_id         uuid        PRIMARY KEY REFERENCES users (id),
    secret_sealed   bytea       NOT NULL,
    enabled_at      timestamptz,                         -- NULL: configuración pendiente de confirmar
    last_used_step  bigint      NOT NULL DEFAULT 0,      -- antirreplay: un código sirve una sola vez
    failed_count    integer     NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    locked_until    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT, UPDATE, DELETE ON user_two_factor TO ${app_role};

CREATE TABLE user_recovery_codes (
    user_id     uuid        NOT NULL REFERENCES users (id),
    code_hash   bytea       NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, code_hash)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON user_recovery_codes TO ${app_role};

-- Paso intermedio del inicio de sesión: contraseña verificada, falta el código. 256 bits, solo el hash.
CREATE TABLE two_factor_challenges (
    token_hash      bytea       PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id),
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX two_factor_challenges_expiry_idx ON two_factor_challenges (expires_at);
GRANT SELECT, INSERT, UPDATE, DELETE ON two_factor_challenges TO ${app_role};
