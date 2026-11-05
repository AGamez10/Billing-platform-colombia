-- =====================================================================================
-- V5 — Planes, suscripciones, consumo y auditoría
-- Los precios y límites de cada plan son una decisión comercial pendiente: la tabla no se
-- siembra con valores inventados. NULL en un límite significa "sin límite".
-- =====================================================================================

CREATE TABLE plans (
    code                 varchar(30)   PRIMARY KEY,
    name                 varchar(100)  NOT NULL,
    monthly_documents    integer       CHECK (monthly_documents IS NULL OR monthly_documents >= 0),
    max_users            integer       CHECK (max_users IS NULL OR max_users >= 1),
    monthly_price_cop    numeric(14,2) CHECK (monthly_price_cop IS NULL OR monthly_price_cop >= 0),
    public               boolean       NOT NULL DEFAULT true,
    active               boolean       NOT NULL DEFAULT true
);

-- Condiciones particulares (p. ej. un convenio con un cliente grande) se expresan como
-- sobrescrituras genéricas de límites/precio, no como código especial por cliente.
CREATE TABLE subscriptions (
    tenant_id             uuid          NOT NULL REFERENCES organizations (id),
    id                    uuid          NOT NULL,
    plan_code             varchar(30)   NOT NULL REFERENCES plans (code),
    status                varchar(20)   NOT NULL CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'CANCELLED')),
    current_period_start  date          NOT NULL,
    current_period_end    date          NOT NULL,
    override_monthly_documents integer,
    override_max_users    integer,
    override_price_cop    numeric(14,2),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT subscriptions_period CHECK (current_period_end > current_period_start)
);
CREATE UNIQUE INDEX subscriptions_one_current ON subscriptions (tenant_id) WHERE status IN ('TRIAL', 'ACTIVE', 'PAST_DUE');
SELECT app_enable_tenant_rls('subscriptions');

-- Medición de consumo. Idempotente: un documento se cuenta una sola vez por tipo de evento.
-- provider_unit_cost queda NULL hasta que exista un contrato con el proveedor que lo defina.
CREATE TABLE usage_events (
    tenant_id           uuid          NOT NULL REFERENCES organizations (id),
    id                  uuid          NOT NULL,
    kind                varchar(30)   NOT NULL CHECK (kind IN ('DOCUMENT_ISSUED', 'DOCUMENT_ACCEPTED')),
    document_id         uuid          NOT NULL,
    provider_id         varchar(32),
    environment         varchar(20),
    provider_unit_cost  numeric(14,4),
    occurred_at         timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT usage_events_once UNIQUE (tenant_id, kind, document_id)
);
CREATE INDEX usage_events_period_idx ON usage_events (tenant_id, occurred_at);
SELECT app_enable_tenant_rls('usage_events');

-- Auditoría de operaciones críticas. Solo inserción: la aplicación no puede modificar ni borrar.
CREATE TABLE audit_log (
    tenant_id      uuid          NOT NULL REFERENCES organizations (id),
    id             bigint        GENERATED ALWAYS AS IDENTITY,
    occurred_at    timestamptz   NOT NULL DEFAULT now(),
    actor_user_id  uuid,
    actor_api_key  uuid,
    action         varchar(60)   NOT NULL,
    resource_type  varchar(40)   NOT NULL,
    resource_id    varchar(100),
    source_ip      inet,
    details        jsonb         NOT NULL DEFAULT '{}',
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX audit_log_time_idx ON audit_log (tenant_id, occurred_at DESC);
SELECT app_enable_tenant_rls('audit_log');

GRANT SELECT ON plans TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON subscriptions TO ${app_role};
GRANT SELECT, INSERT ON usage_events TO ${app_role};
GRANT SELECT, INSERT ON audit_log TO ${app_role};
