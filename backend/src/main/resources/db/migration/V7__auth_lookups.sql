-- =====================================================================================
-- V7 — Consultas de autenticación previas al tenant
-- Al iniciar sesión el usuario aún no tiene organización en contexto, y organizations
-- tiene RLS por tenant. Esta función devuelve SOLO las membresías activas del usuario dado
-- en organizaciones activas, con el nombre para que elija organización.
-- =====================================================================================

CREATE FUNCTION auth_user_memberships(p_user_id uuid)
    RETURNS TABLE (tenant_id uuid, role varchar, legal_name varchar)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT m.tenant_id, m.role, o.legal_name
    FROM memberships m JOIN organizations o ON o.id = m.tenant_id
    WHERE m.user_id = p_user_id AND m.status = 'ACTIVE' AND o.status = 'ACTIVE'
    ORDER BY o.legal_name
$$;

REVOKE ALL ON FUNCTION auth_user_memberships(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_user_memberships(uuid) TO ${app_role};
