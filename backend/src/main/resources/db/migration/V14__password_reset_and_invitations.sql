-- =====================================================================================
-- V14 — Recuperar contraseña e invitar al equipo
-- Ambos flujos usan enlaces de un solo uso con 256 bits aleatorios; se guarda solo su SHA-256.
-- =====================================================================================

-- Recuperación de contraseña: dato de la cuenta del usuario, no de una organización (como users).
CREATE TABLE password_reset_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id),
    token_hash  bytea       NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX password_reset_tokens_hash_uk ON password_reset_tokens (token_hash);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id, created_at DESC);
GRANT SELECT, INSERT, UPDATE ON password_reset_tokens TO ${app_role};

-- Invitaciones al equipo de una organización. El rol nunca es OWNER (el dueño no se invita).
CREATE TABLE invitations (
    tenant_id         uuid         NOT NULL REFERENCES organizations (id),
    id                uuid         NOT NULL,
    email             varchar(254) NOT NULL CHECK (email = lower(email)),
    role              varchar(20)  NOT NULL CHECK (role IN ('ADMIN', 'BILLER', 'VIEWER')),
    token_hash        bytea        NOT NULL,
    invited_by        uuid         NOT NULL REFERENCES users (id),
    expires_at        timestamptz  NOT NULL,
    accepted_at       timestamptz,
    accepted_user_id  uuid         REFERENCES users (id),
    revoked_at        timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CHECK ((accepted_at IS NULL) = (accepted_user_id IS NULL))
);
CREATE UNIQUE INDEX invitations_token_uk ON invitations (token_hash);
-- A lo sumo una invitación abierta por correo y organización (reenviar = revocar + crear).
CREATE UNIQUE INDEX invitations_one_open_per_email ON invitations (tenant_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
SELECT app_enable_tenant_rls('invitations');
GRANT SELECT, INSERT, UPDATE ON invitations TO ${app_role};

-- Quien abre el enlace aún no tiene sesión ni tenant: se ubica la invitación por el hash del
-- token, solo en organizaciones activas, con el nombre de la organización para mostrarlo.
CREATE FUNCTION invitation_find(p_token_hash bytea)
    RETURNS TABLE (tenant_id uuid, id uuid, email varchar, role varchar, organization_name varchar,
                   expires_at timestamptz, accepted_at timestamptz, revoked_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT i.tenant_id, i.id, i.email, i.role, o.legal_name, i.expires_at, i.accepted_at, i.revoked_at
    FROM invitations i JOIN organizations o ON o.id = i.tenant_id
    WHERE i.token_hash = p_token_hash AND o.status = 'ACTIVE'
$$;
REVOKE ALL ON FUNCTION invitation_find(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invitation_find(bytea) TO ${app_role};
