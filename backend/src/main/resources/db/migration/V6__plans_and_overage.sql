-- =====================================================================================
-- V6 — Planes aprobados por el propietario (2026-11-05) y excedentes con consentimiento
-- Ver docs/PRICING.md, ADR-0011 y ADR-0014. Precios en COP antes de IVA.
-- =====================================================================================

-- La cuota puede ser por ciclo mensual (planes pagos, aun con pago anual) o total (prueba gratis).
ALTER TABLE plans RENAME COLUMN monthly_documents TO quota_documents;
ALTER TABLE plans
    ADD COLUMN quota_scope              varchar(10)   NOT NULL DEFAULT 'MONTHLY' CHECK (quota_scope IN ('MONTHLY', 'TOTAL')),
    ADD COLUMN annual_price_cop         numeric(14,2) CHECK (annual_price_cop IS NULL OR annual_price_cop >= 0),
    ADD COLUMN monthly_billing_allowed  boolean       NOT NULL DEFAULT true,
    ADD COLUMN overage_unit_price_cop   numeric(14,2) CHECK (overage_unit_price_cop IS NULL OR overage_unit_price_cop > 0),
    ADD CONSTRAINT plans_has_a_billing_option CHECK (monthly_billing_allowed OR annual_price_cop IS NOT NULL),
    ADD CONSTRAINT plans_trial_without_overage CHECK (quota_scope = 'MONTHLY' OR overage_unit_price_cop IS NULL);

-- max_users NULL = usuarios ilimitados en todos los planes (decisión de producto).
INSERT INTO plans (code, name, quota_documents, quota_scope, max_users, monthly_price_cop, annual_price_cop,
                   monthly_billing_allowed, overage_unit_price_cop, public, active) VALUES
    ('TRIAL',       'Prueba gratis',  20, 'TOTAL',   NULL,     0,      NULL, true,  NULL, true, true),
    ('MICRO',       'Micro',          15, 'MONTHLY', NULL,  6900,  69000.00, false, 150,  true, true),
    ('EMPRENDEDOR', 'Emprendedor',    40, 'MONTHLY', NULL, 12900, 129000.00, true,  150,  true, true),
    ('NEGOCIO',     'Negocio',       120, 'MONTHLY', NULL, 29900, 299000.00, true,  150,  true, true),
    ('PRO',         'Pro',           300, 'MONTHLY', NULL, 69900, 699000.00, true,  150,  true, true);

ALTER TABLE subscriptions RENAME COLUMN override_monthly_documents TO override_quota_documents;
ALTER TABLE subscriptions
    ADD COLUMN billing_interval varchar(10) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_interval IN ('MONTHLY', 'ANNUAL', 'NONE'));

-- Precio del excedente congelado en cada documento cobrable (NULL = dentro del plan).
ALTER TABLE usage_events ADD COLUMN overage_unit_price_cop numeric(14,2) CHECK (overage_unit_price_cop IS NULL OR overage_unit_price_cop > 0);
CREATE INDEX usage_events_billable_overage_idx ON usage_events (tenant_id, occurred_at) WHERE overage_unit_price_cop IS NOT NULL;

-- Aceptación expresa de excedentes: una por suscripción y ciclo, con precio congelado y autor.
CREATE TABLE overage_consents (
    tenant_id            uuid          NOT NULL,
    subscription_id      uuid          NOT NULL,
    window_start         date          NOT NULL,
    window_end           date          NOT NULL,
    unit_price_cop       numeric(14,2) NOT NULL CHECK (unit_price_cop > 0),
    accepted_by_user     uuid          REFERENCES users (id),
    accepted_by_api_key  uuid,
    accepted_at          timestamptz   NOT NULL,
    PRIMARY KEY (tenant_id, subscription_id, window_start),
    FOREIGN KEY (tenant_id, subscription_id) REFERENCES subscriptions (tenant_id, id),
    CONSTRAINT overage_consents_window CHECK (window_end > window_start),
    CONSTRAINT overage_consents_one_actor CHECK ((accepted_by_user IS NULL) <> (accepted_by_api_key IS NULL))
);
SELECT app_enable_tenant_rls('overage_consents');

GRANT SELECT, INSERT ON overage_consents TO ${app_role};
