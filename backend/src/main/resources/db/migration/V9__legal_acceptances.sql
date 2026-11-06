-- Aceptación de términos y política de datos: quién, qué versión y cuándo (exigido por los
-- propios documentos legales y por la Ley 1581 de 2012 para la autorización de datos).
-- Solo inserción: una aceptación no se edita ni se borra; una versión nueva es otra fila.
CREATE TABLE legal_acceptances (
    tenant_id   uuid        NOT NULL REFERENCES organizations (id),
    id          uuid        NOT NULL,
    user_id     uuid        NOT NULL REFERENCES users (id),
    document    varchar(20) NOT NULL CHECK (document IN ('TERMS', 'PRIVACY')),
    version     varchar(40) NOT NULL CHECK (version ~ '^[0-9A-Za-z._-]{1,40}$'),
    accepted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE INDEX legal_acceptances_user_idx ON legal_acceptances (tenant_id, user_id, document, accepted_at DESC);

SELECT app_enable_tenant_rls('legal_acceptances');
GRANT SELECT, INSERT ON legal_acceptances TO ${app_role};
