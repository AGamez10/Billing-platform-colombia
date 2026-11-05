-- =====================================================================================
-- V1 — Base multi-tenant e identidad
-- Estrategia (ADR-0002): base y esquema compartidos, columna tenant_id en toda tabla de
-- tenant, y Row-Level Security como segunda barrera detrás del filtro de la aplicación.
-- El tenant de la transacción lo fija la aplicación con
--   SELECT set_config('app.tenant_id', :tenant, true)   -- (local a la transacción)
-- y sale de la identidad autenticada, nunca de datos enviados por el cliente.
-- Placeholder Flyway: ${app_role} = rol de la aplicación (sin ownership ni BYPASSRLS):
-- RLS SIEMPRE le aplica. El rol dueño (migraciones) tiene BYPASSRLS a propósito: lo
-- necesitan las pocas funciones SECURITY DEFINER de autenticación/registro/cola, que
-- operan antes de existir un tenant en contexto y por eso filtran explícitamente.
-- =====================================================================================

-- Tenant actual de la transacción; NULL si no se fijó => las políticas no devuelven filas.
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- Usuario autenticado de la transacción (solo para leer sus propias membresías al iniciar sesión).
CREATE FUNCTION app_current_user() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::uuid $$;

-- Aplica la política estándar de aislamiento a una tabla con columna tenant_id.
CREATE FUNCTION app_enable_tenant_rls(target regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s USING (tenant_id = app_current_tenant()) '
        'WITH CHECK (tenant_id = app_current_tenant())', target);
END;
$$;

-- ---------------------------------------------------------------- Organizaciones (tenants)
CREATE TABLE organizations (
    id               uuid PRIMARY KEY,
    legal_name       varchar(450) NOT NULL,
    trade_name       varchar(450),
    id_type          varchar(30)  NOT NULL,
    id_number        varchar(20)  NOT NULL,
    check_digit      smallint,
    email            varchar(254),
    status           varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT organizations_nit_dv CHECK (id_type <> 'NIT' OR check_digit IS NOT NULL),
    CONSTRAINT organizations_identification_uk UNIQUE (id_type, id_number)
);
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON organizations
    USING (id = app_current_tenant()) WITH CHECK (id = app_current_tenant());

-- ---------------------------------------------------------------- Usuarios
-- Los usuarios son globales (una persona puede pertenecer a varias organizaciones);
-- no contienen datos de ninguna organización. El acceso a datos pasa por memberships.
CREATE TABLE users (
    id                  uuid PRIMARY KEY,
    email               varchar(254) NOT NULL,
    password_hash       varchar(255) NOT NULL,            -- Argon2id (formato PHC), nunca texto plano
    full_name           varchar(200) NOT NULL,
    status              varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    email_verified_at   timestamptz,
    failed_login_count  integer      NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lowercase CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_uk ON users (email);

CREATE TABLE memberships (
    tenant_id   uuid        NOT NULL REFERENCES organizations (id),
    user_id     uuid        NOT NULL REFERENCES users (id),
    role        varchar(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'BILLER', 'VIEWER')),
    status      varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX memberships_user_idx ON memberships (user_id);
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
-- Dentro de un tenant se ven sus membresías; fuera de él, un usuario solo ve las suyas
-- (necesario para elegir organización al iniciar sesión). Solo se escribe dentro del tenant.
CREATE POLICY tenant_or_self_read ON memberships FOR SELECT
    USING (tenant_id = app_current_tenant() OR user_id = app_current_user());
CREATE POLICY tenant_write ON memberships FOR INSERT WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_update ON memberships FOR UPDATE
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_delete ON memberships FOR DELETE USING (tenant_id = app_current_tenant());

-- Refresh tokens opacos, guardados como hash SHA-256; rotación con detección de reúso por familia.
CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY,
    user_id      uuid         NOT NULL REFERENCES users (id),
    tenant_id    uuid         NOT NULL REFERENCES organizations (id),
    family_id    uuid         NOT NULL,
    token_hash   bytea        NOT NULL,
    expires_at   timestamptz  NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX refresh_tokens_hash_uk ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
-- Un usuario solo opera sobre sus propios tokens; la búsqueda inicial (aún sin usuario en
-- contexto) pasa por auth_find_refresh_token.
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY own_tokens ON refresh_tokens
    USING (user_id = app_current_user()) WITH CHECK (user_id = app_current_user());

CREATE FUNCTION auth_find_refresh_token(p_token_hash bytea)
    RETURNS TABLE (id uuid, user_id uuid, tenant_id uuid, family_id uuid, expires_at timestamptz,
                   revoked_at timestamptz, replaced_by uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT r.id, r.user_id, r.tenant_id, r.family_id, r.expires_at, r.revoked_at, r.replaced_by
    FROM refresh_tokens r WHERE r.token_hash = p_token_hash
$$;
REVOKE ALL ON FUNCTION auth_find_refresh_token(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_find_refresh_token(bytea) TO ${app_role};

-- Claves de API por organización (integraciones como AdoptaFácil). Se muestra la clave
-- completa una sola vez; se guarda prefijo público + hash SHA-256 del secreto.
CREATE TABLE api_keys (
    tenant_id     uuid         NOT NULL REFERENCES organizations (id),
    id            uuid         NOT NULL,
    name          varchar(100) NOT NULL,
    key_prefix    varchar(16)  NOT NULL,
    key_hash      bytea        NOT NULL,
    scopes        text[]       NOT NULL,
    created_by    uuid         REFERENCES users (id),
    last_used_at  timestamptz,
    expires_at    timestamptz,
    revoked_at    timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX api_keys_prefix_uk ON api_keys (key_prefix);
SELECT app_enable_tenant_rls('api_keys');

-- Autenticación por clave de API: ocurre antes de conocer el tenant. Función acotada
-- (SECURITY DEFINER) que solo devuelve lo necesario para verificar una clave concreta;
-- la aplicación compara el hash en tiempo constante.
CREATE FUNCTION auth_find_api_key(p_prefix varchar)
    RETURNS TABLE (tenant_id uuid, id uuid, key_hash bytea, scopes text[], expires_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT k.tenant_id, k.id, k.key_hash, k.scopes, k.expires_at
    FROM api_keys k JOIN organizations o ON o.id = k.tenant_id
    WHERE k.key_prefix = p_prefix AND k.revoked_at IS NULL AND o.status = 'ACTIVE'
$$;

-- Registro de una organización nueva: aún no hay tenant en contexto. Crea organización,
-- usuario dueño y membresía de forma atómica; solo se expone este flujo concreto.
CREATE FUNCTION register_organization(
    p_org_id uuid, p_legal_name varchar, p_id_type varchar, p_id_number varchar, p_check_digit smallint,
    p_org_email varchar, p_user_id uuid, p_user_email varchar, p_password_hash varchar, p_full_name varchar)
    RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO organizations (id, legal_name, id_type, id_number, check_digit, email)
    VALUES (p_org_id, p_legal_name, p_id_type, p_id_number, p_check_digit, p_org_email);
    INSERT INTO users (id, email, password_hash, full_name)
    VALUES (p_user_id, lower(p_user_email), p_password_hash, p_full_name);
    INSERT INTO memberships (tenant_id, user_id, role) VALUES (p_org_id, p_user_id, 'OWNER');
END;
$$;

REVOKE ALL ON FUNCTION auth_find_api_key(varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION register_organization(uuid, varchar, varchar, varchar, smallint, varchar, uuid, varchar, varchar, varchar) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_enable_tenant_rls(regclass) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_find_api_key(varchar) TO ${app_role};
GRANT EXECUTE ON FUNCTION register_organization(uuid, varchar, varchar, varchar, smallint, varchar, uuid, varchar, varchar, varchar) TO ${app_role};

GRANT SELECT, INSERT, UPDATE ON organizations, users, memberships, refresh_tokens, api_keys TO ${app_role};
