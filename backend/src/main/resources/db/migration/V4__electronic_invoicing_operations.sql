-- =====================================================================================
-- V4 — Cuentas de proveedor, secretos, cola de envíos, webhooks e idempotencia de API
-- =====================================================================================

-- Secretos de proveedor cifrados por la aplicación (AES-256-GCM, llave maestra fuera de la
-- base de datos). La base nunca ve un secreto en claro.
CREATE TABLE provider_secrets (
    tenant_id    uuid         NOT NULL REFERENCES organizations (id),
    ref          varchar(100) NOT NULL,
    ciphertext   bytea        NOT NULL,
    nonce        bytea        NOT NULL CHECK (length(nonce) = 12),
    key_version  smallint     NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, ref)
);
SELECT app_enable_tenant_rls('provider_secrets');

CREATE TABLE provider_accounts (
    tenant_id               uuid         NOT NULL REFERENCES organizations (id),
    id                      uuid         NOT NULL,
    provider_id             varchar(32)  NOT NULL CHECK (provider_id ~ '^[a-z][a-z0-9-]{1,31}$'),
    environment             varchar(20)  NOT NULL CHECK (environment IN ('SIMULATION', 'HABILITATION', 'PRODUCTION')),
    credentials_secret_ref  varchar(100),
    webhook_secret_ref      varchar(100),
    webhook_token_hash      bytea,                 -- SHA-256 del token opaco de la URL de webhook
    active                  boolean      NOT NULL DEFAULT false,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, credentials_secret_ref) REFERENCES provider_secrets (tenant_id, ref),
    FOREIGN KEY (tenant_id, webhook_secret_ref) REFERENCES provider_secrets (tenant_id, ref)
);
-- Una sola cuenta activa por tenant: es la que recibe los nuevos envíos (enrutamiento por configuración).
CREATE UNIQUE INDEX provider_accounts_one_active ON provider_accounts (tenant_id) WHERE active;
CREATE UNIQUE INDEX provider_accounts_webhook_uk ON provider_accounts (webhook_token_hash) WHERE webhook_token_hash IS NOT NULL;
SELECT app_enable_tenant_rls('provider_accounts');

ALTER TABLE invoices
    ADD FOREIGN KEY (tenant_id, provider_account_id) REFERENCES provider_accounts (tenant_id, id);

-- Webhooks llegan sin tenant en contexto: se resuelve la cuenta por el hash del token
-- de la URL. La firma se verifica después, en la aplicación.
CREATE FUNCTION webhook_find_account(p_token_hash bytea)
    RETURNS TABLE (tenant_id uuid, id uuid, provider_id varchar, environment varchar,
                   credentials_secret_ref varchar, webhook_secret_ref varchar)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT a.tenant_id, a.id, a.provider_id, a.environment, a.credentials_secret_ref, a.webhook_secret_ref
    FROM provider_accounts a JOIN organizations o ON o.id = a.tenant_id
    WHERE a.webhook_token_hash = p_token_hash AND o.status = 'ACTIVE'
$$;

CREATE TABLE processed_webhook_events (
    provider_id  varchar(32)  NOT NULL,
    event_id     varchar(200) NOT NULL,
    tenant_id    uuid         NOT NULL REFERENCES organizations (id),
    received_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id, event_id)
);
SELECT app_enable_tenant_rls('processed_webhook_events');

-- ---------------------------------------------------------------- Cola de envíos (outbox)
-- Al finalizar una factura se inserta el job en la MISMA transacción (patrón outbox):
-- nunca hay una factura numerada sin envío programado ni un envío sin factura.
-- Sin RabbitMQ/Kafka: PostgreSQL con SKIP LOCKED basta para el volumen esperado (ADR-0006).
CREATE TABLE submission_jobs (
    tenant_id        uuid         NOT NULL,
    id               uuid         NOT NULL,
    invoice_id       uuid         NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'DONE', 'DEAD')),
    attempts         integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    locked_until     timestamptz,
    last_error       varchar(1000),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id)
);
CREATE UNIQUE INDEX submission_jobs_one_open_per_invoice ON submission_jobs (tenant_id, invoice_id) WHERE status = 'QUEUED';
CREATE INDEX submission_jobs_due_idx ON submission_jobs (next_attempt_at) WHERE status = 'QUEUED';
SELECT app_enable_tenant_rls('submission_jobs');

-- El worker no tiene tenant hasta reclamar un job. Esta función reclama hasta p_limit jobs
-- vencidos (lease de p_lease_seconds) sin que dos workers tomen el mismo; luego el worker
-- procesa cada job en su propia transacción con app.tenant_id del job.
CREATE FUNCTION claim_submission_jobs(p_limit integer, p_lease_seconds integer)
    RETURNS TABLE (tenant_id uuid, job_id uuid, invoice_id uuid, attempts integer)
    LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    WITH due AS (
        SELECT j.tenant_id, j.id
        FROM submission_jobs j
        WHERE j.status = 'QUEUED' AND j.next_attempt_at <= now()
          AND (j.locked_until IS NULL OR j.locked_until < now())
        ORDER BY j.next_attempt_at
        LIMIT LEAST(GREATEST(p_limit, 1), 100)
        FOR UPDATE SKIP LOCKED
    )
    UPDATE submission_jobs j
       SET locked_until = now() + make_interval(secs => LEAST(GREATEST(p_lease_seconds, 10), 600)),
           attempts = j.attempts + 1
      FROM due
     WHERE j.tenant_id = due.tenant_id AND j.id = due.id
    RETURNING j.tenant_id, j.id, j.invoice_id, j.attempts
$$;

-- ---------------------------------------------------------------- Idempotencia de la API
-- Cabecera Idempotency-Key en POST: misma clave + mismo cuerpo => misma respuesta;
-- misma clave + cuerpo distinto => 422. Expira a las 24 h.
CREATE TABLE api_idempotency_keys (
    tenant_id        uuid         NOT NULL REFERENCES organizations (id),
    idempotency_key  varchar(128) NOT NULL,
    request_hash     bytea        NOT NULL,
    response_status  smallint,
    response_body    jsonb,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL DEFAULT now() + interval '24 hours',
    PRIMARY KEY (tenant_id, idempotency_key)
);
CREATE INDEX api_idempotency_keys_expiry_idx ON api_idempotency_keys (expires_at);
SELECT app_enable_tenant_rls('api_idempotency_keys');

REVOKE ALL ON FUNCTION webhook_find_account(bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION claim_submission_jobs(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION webhook_find_account(bytea) TO ${app_role};
GRANT EXECUTE ON FUNCTION claim_submission_jobs(integer, integer) TO ${app_role};

GRANT SELECT, INSERT ON provider_secrets TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON provider_accounts, submission_jobs TO ${app_role};
GRANT SELECT, INSERT ON processed_webhook_events TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON api_idempotency_keys TO ${app_role};
