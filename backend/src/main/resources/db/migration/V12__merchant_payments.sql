-- Cobro en tienda (ADR-0020): cada comercio conecta SU cuenta de pasarela. La plataforma no
-- recibe el dinero de las ventas; solo crea el cobro, escucha la confirmación y emite el POS.

CREATE TABLE payment_accounts (
    tenant_id           uuid         NOT NULL REFERENCES organizations (id),
    id                  uuid         NOT NULL,
    gateway             varchar(20)  NOT NULL CHECK (gateway IN ('BOLD')),
    mode                varchar(10)  NOT NULL CHECK (mode IN ('TEST', 'LIVE')),
    identity_key_ref    varchar(100) NOT NULL,
    secret_key_ref      varchar(100) NOT NULL,
    webhook_token_hash  bytea        NOT NULL,      -- SHA-256 del token opaco de la URL de webhook
    active              boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, identity_key_ref) REFERENCES provider_secrets (tenant_id, ref),
    FOREIGN KEY (tenant_id, secret_key_ref) REFERENCES provider_secrets (tenant_id, ref)
);
CREATE UNIQUE INDEX payment_accounts_one_active ON payment_accounts (tenant_id) WHERE active;
CREATE UNIQUE INDEX payment_accounts_webhook_uk ON payment_accounts (webhook_token_hash);
SELECT app_enable_tenant_rls('payment_accounts');

CREATE TABLE charges (
    tenant_id               uuid          NOT NULL REFERENCES organizations (id),
    id                      uuid          NOT NULL,
    invoice_id              uuid          NOT NULL,
    payment_account_id      uuid          NOT NULL,
    created_by              uuid          NOT NULL REFERENCES users (id),  -- quien inició el cobro; firma la emisión
    amount                  numeric(18,2) NOT NULL CHECK (amount > 0),
    status                  varchar(20)   NOT NULL CHECK (status IN ('PENDING', 'PAID', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    gateway_link_id         varchar(60),
    checkout_url            varchar(500),
    gateway_transaction_id  varchar(100),
    payment_method          varchar(40),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    paid_at                 timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id),
    FOREIGN KEY (tenant_id, payment_account_id) REFERENCES payment_accounts (tenant_id, id),
    CHECK (status <> 'PAID' OR paid_at IS NOT NULL)
);
-- Una venta tiene a lo sumo un cobro vivo (pendiente o pagado): nunca se cobra dos veces.
CREATE UNIQUE INDEX charges_one_live_per_sale ON charges (tenant_id, invoice_id) WHERE status IN ('PENDING', 'PAID');
CREATE INDEX charges_pending_idx ON charges (tenant_id, created_at) WHERE status = 'PENDING';
SELECT app_enable_tenant_rls('charges');

-- El webhook llega sin tenant: se ubica la cuenta por el hash del token de su URL.
CREATE FUNCTION payment_webhook_find_account(p_token_hash bytea)
    RETURNS TABLE (tenant_id uuid, id uuid, gateway varchar, mode varchar, identity_key_ref varchar, secret_key_ref varchar)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT a.tenant_id, a.id, a.gateway, a.mode, a.identity_key_ref, a.secret_key_ref
    FROM payment_accounts a JOIN organizations o ON o.id = a.tenant_id
    WHERE a.webhook_token_hash = p_token_hash AND a.active AND o.status = 'ACTIVE'
$$;
REVOKE ALL ON FUNCTION payment_webhook_find_account(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION payment_webhook_find_account(bytea) TO ${app_role};

-- Conciliación periódica (sin tenant): reclama cobros pendientes para consultarlos a la pasarela.
-- Así un pago confirmado nunca queda sin su documento, aunque se cierre la pantalla de cobro o
-- el webhook no llegue. El lease (updated_at) evita que dos réplicas consulten el mismo cobro a la vez.
CREATE FUNCTION claim_pending_charges(p_limit integer)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    UPDATE charges c SET updated_at = now()
    FROM (
        SELECT c2.tenant_id, c2.id FROM charges c2
        WHERE c2.status = 'PENDING'
          AND c2.created_at > now() - interval '24 hours'
          AND c2.updated_at < now() - interval '30 seconds'
        ORDER BY c2.updated_at
        LIMIT p_limit
        FOR UPDATE SKIP LOCKED
    ) picked
    WHERE c.tenant_id = picked.tenant_id AND c.id = picked.id
    RETURNING c.tenant_id, c.id
$$;
REVOKE ALL ON FUNCTION claim_pending_charges(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_pending_charges(integer) TO ${app_role};

GRANT SELECT, INSERT, UPDATE ON payment_accounts, charges TO ${app_role};
