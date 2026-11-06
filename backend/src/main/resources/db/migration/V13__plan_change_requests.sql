-- Oferta de mejora de plan (ADR-0014/0021): la solicitud guarda el precio cotizado. El plan
-- cambia solo cuando se confirma el pago (fuera de esta tabla).
CREATE TABLE plan_change_requests (
    tenant_id      uuid          NOT NULL REFERENCES organizations (id),
    id             uuid          NOT NULL,
    from_plan      varchar(30)   NOT NULL REFERENCES plans (code),
    to_plan        varchar(30)   NOT NULL REFERENCES plans (code),
    cycle          varchar(10)   NOT NULL CHECK (cycle IN ('MONTHLY', 'ANNUAL')),
    quoted_price_cop numeric(14,2) NOT NULL CHECK (quoted_price_cop > 0),
    requested_by   uuid          NOT NULL REFERENCES users (id),
    status         varchar(12)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED')),
    created_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX plan_change_requests_one_pending ON plan_change_requests (tenant_id) WHERE status = 'PENDING';
SELECT app_enable_tenant_rls('plan_change_requests');
GRANT SELECT, INSERT, UPDATE ON plan_change_requests TO ${app_role};
