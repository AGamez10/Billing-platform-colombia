-- Claves de API en uso (ADR-0032) y avisos salientes firmados hacia otras apps (ADR-0033).

-- Último uso de una clave, sin tenant en contexto (la petición aún no lo tiene). A lo sumo una
-- escritura por minuto por clave para no convertir cada petición en un UPDATE.
CREATE FUNCTION auth_touch_api_key(p_id uuid) RETURNS boolean
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    WITH t AS (
        UPDATE api_keys SET last_used_at = now()
        WHERE id = p_id AND revoked_at IS NULL AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
        RETURNING 1)
    SELECT EXISTS (SELECT 1 FROM t)
$$;
REVOKE ALL ON FUNCTION auth_touch_api_key(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_touch_api_key(uuid) TO ${app_role};

-- La aplicación solo puede revocar claves, no cambiar su hash, prefijo, permisos ni dueño.
REVOKE UPDATE ON api_keys FROM ${app_role};
GRANT UPDATE (revoked_at) ON api_keys TO ${app_role};

-- ------------------------------------------------------------------ webhooks salientes
CREATE TABLE webhook_endpoints (
    tenant_id     uuid          NOT NULL REFERENCES organizations (id),
    id            uuid          NOT NULL,
    url           varchar(500)  NOT NULL CHECK (url ~ '^https://'),
    events        text[]        NOT NULL CHECK (cardinality(events) > 0),
    secret_ref    varchar(100)  NOT NULL,      -- secreto de firma cifrado (provider_secrets)
    description   varchar(200),
    active        boolean       NOT NULL DEFAULT true,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    disabled_at   timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, secret_ref) REFERENCES provider_secrets (tenant_id, ref)
);
SELECT app_enable_tenant_rls('webhook_endpoints');
GRANT SELECT, INSERT ON webhook_endpoints TO ${app_role};
GRANT UPDATE (active, disabled_at, description) ON webhook_endpoints TO ${app_role};

CREATE TABLE webhook_deliveries (
    tenant_id        uuid         NOT NULL REFERENCES organizations (id),
    id               uuid         NOT NULL,
    endpoint_id      uuid         NOT NULL,
    event_type       varchar(40)  NOT NULL,
    payload          jsonb        NOT NULL,
    status           varchar(12)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    leased_until     timestamptz,
    last_status      integer,
    last_error       varchar(300),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    delivered_at     timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, endpoint_id) REFERENCES webhook_endpoints (tenant_id, id)
);
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_deliveries_endpoint_idx ON webhook_deliveries (tenant_id, endpoint_id, created_at DESC);
SELECT app_enable_tenant_rls('webhook_deliveries');
GRANT SELECT, INSERT ON webhook_deliveries TO ${app_role};
GRANT UPDATE (status, attempts, next_attempt_at, leased_until, last_status, last_error, delivered_at) ON webhook_deliveries TO ${app_role};

-- Sin tenant: entregas vencidas para el trabajador, con arriendo de 2 minutos (varias instancias
-- no envían la misma a la vez). Solo devuelve ids.
CREATE FUNCTION claim_webhook_deliveries(p_limit integer) RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    UPDATE webhook_deliveries d SET leased_until = now() + interval '2 minutes'
    FROM (SELECT tenant_id, id FROM webhook_deliveries
          WHERE status = 'PENDING' AND next_attempt_at <= now() AND (leased_until IS NULL OR leased_until < now())
          ORDER BY next_attempt_at LIMIT p_limit FOR UPDATE SKIP LOCKED) due
    WHERE d.tenant_id = due.tenant_id AND d.id = due.id
    RETURNING d.tenant_id, d.id
$$;
REVOKE ALL ON FUNCTION claim_webhook_deliveries(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_webhook_deliveries(integer) TO ${app_role};
