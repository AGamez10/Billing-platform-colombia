-- =====================================================================================
-- V15 — Pago de NUESTRAS suscripciones con Mercado Pago (ADR-0024)
-- Cada intento de pago es un "checkout" por un plan, un ciclo y un valor fijos. El plan solo
-- cambia cuando Mercado Pago confirma un pago aprobado por exactamente ese valor.
-- =====================================================================================

CREATE TABLE subscription_checkouts (
    tenant_id           uuid          NOT NULL REFERENCES organizations (id),
    id                  uuid          NOT NULL,
    plan_code           varchar(30)   NOT NULL REFERENCES plans (code),
    cycle               varchar(10)   NOT NULL CHECK (cycle IN ('MONTHLY', 'ANNUAL')),
    amount              numeric(14,2) NOT NULL CHECK (amount > 0),
    status              varchar(12)   NOT NULL CHECK (status IN ('PENDING', 'PAID', 'EXPIRED')),
    gateway             varchar(20)   NOT NULL CHECK (gateway IN ('MERCADOPAGO')),
    preference_id       varchar(100),
    checkout_url        varchar(600),
    gateway_payment_id  varchar(60),
    created_by          uuid          NOT NULL REFERENCES users (id),
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    paid_at             timestamptz,
    PRIMARY KEY (tenant_id, id),
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL AND gateway_payment_id IS NOT NULL))
);
-- Un pago de Mercado Pago activa a lo sumo un checkout, en toda la plataforma.
CREATE UNIQUE INDEX subscription_checkouts_payment_uk ON subscription_checkouts (gateway_payment_id) WHERE gateway_payment_id IS NOT NULL;
CREATE INDEX subscription_checkouts_pending_idx ON subscription_checkouts (created_at) WHERE status = 'PENDING';
SELECT app_enable_tenant_rls('subscription_checkouts');
GRANT SELECT, INSERT, UPDATE ON subscription_checkouts TO ${app_role};

-- El aviso de Mercado Pago y la conciliación llegan sin tenant: el checkout se ubica por su id
-- (es la external_reference del pago).
CREATE FUNCTION subscription_checkout_tenant(p_id uuid)
    RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT tenant_id FROM subscription_checkouts WHERE id = p_id
$$;
REVOKE ALL ON FUNCTION subscription_checkout_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION subscription_checkout_tenant(uuid) TO ${app_role};

-- Conciliación periódica: checkouts pendientes de las últimas 48 h, con lease para no repetir.
CREATE FUNCTION claim_pending_checkouts(p_limit integer)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    UPDATE subscription_checkouts c SET updated_at = now()
    FROM (
        SELECT c2.tenant_id, c2.id FROM subscription_checkouts c2
        WHERE c2.status = 'PENDING'
          AND c2.updated_at < now() - interval '60 seconds'
        ORDER BY c2.updated_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    ) picked
    WHERE c.tenant_id = picked.tenant_id AND c.id = picked.id
    RETURNING c.tenant_id, c.id
$$;
REVOKE ALL ON FUNCTION claim_pending_checkouts(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_pending_checkouts(integer) TO ${app_role};

-- Vencimiento: una suscripción pagada cuyo periodo terminó hace más de los días de gracia pasa
-- a PAST_DUE (no emite hasta pagar). Corre sin tenant y solo toca ese cambio de estado.
CREATE FUNCTION mark_overdue_subscriptions(p_today date, p_grace_days integer)
    RETURNS integer
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    WITH changed AS (
        UPDATE subscriptions SET status = 'PAST_DUE'
        WHERE status = 'ACTIVE' AND current_period_end + p_grace_days <= p_today
        RETURNING 1
    )
    SELECT count(*)::integer FROM changed
$$;
REVOKE ALL ON FUNCTION mark_overdue_subscriptions(date, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mark_overdue_subscriptions(date, integer) TO ${app_role};
