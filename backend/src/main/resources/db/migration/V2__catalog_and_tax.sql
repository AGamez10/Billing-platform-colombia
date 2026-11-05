-- =====================================================================================
-- V2 — Catálogo tributario, clientes y productos
-- =====================================================================================

-- Catálogo global de tarifas con vigencia. Datos, no código: cambian por reformas.
-- No puede haber dos tarifas con el mismo código vigentes el mismo día.
CREATE TABLE tax_rates (
    code             varchar(30)  NOT NULL,
    tax_type         varchar(10)  NOT NULL CHECK (tax_type IN ('IVA', 'INC')),
    treatment        varchar(10)  NOT NULL CHECK (treatment IN ('TAXED', 'EXEMPT', 'EXCLUDED')),
    percentage       numeric(5,2) NOT NULL CHECK (percentage BETWEEN 0 AND 100),
    valid_from       date         NOT NULL,
    valid_to         date,
    legal_reference  varchar(300) NOT NULL CHECK (length(trim(legal_reference)) > 0),
    verified_on      date         NOT NULL,
    PRIMARY KEY (code, valid_from),
    CONSTRAINT tax_rates_zero_unless_taxed CHECK (treatment = 'TAXED' OR percentage = 0),
    CONSTRAINT tax_rates_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT tax_rates_no_overlap EXCLUDE USING gist (
        code WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
);

-- Tarifas iniciales. Fuente: Estatuto Tributario (artículos citados). Verificado el
-- 2026-11-05: la tarifa general continúa en 19%; hay un proyecto de reforma en trámite
-- (oct-2026) que movería bienes entre tarifas. La asignación de tarifa a cada producto
-- es responsabilidad del contribuyente/contador; la plataforma no la infiere.
INSERT INTO tax_rates (code, tax_type, treatment, percentage, valid_from, valid_to, legal_reference, verified_on) VALUES
    ('IVA_19',       'IVA', 'TAXED',     19, DATE '2017-01-01', NULL, 'Estatuto Tributario art. 468 (tarifa general)',    DATE '2026-11-05'),
    ('IVA_5',        'IVA', 'TAXED',      5, DATE '2017-01-01', NULL, 'Estatuto Tributario art. 468-1',                    DATE '2026-11-05'),
    ('IVA_EXENTO',   'IVA', 'EXEMPT',     0, DATE '2017-01-01', NULL, 'Estatuto Tributario art. 477 y ss. (bienes exentos)', DATE '2026-11-05'),
    ('IVA_EXCLUIDO', 'IVA', 'EXCLUDED',   0, DATE '2017-01-01', NULL, 'Estatuto Tributario art. 424 y ss. (bienes excluidos)', DATE '2026-11-05'),
    ('INC_8',        'INC', 'TAXED',      8, DATE '2017-01-01', NULL, 'Estatuto Tributario art. 512-9 (restaurantes y bares)', DATE '2026-11-05');

-- ---------------------------------------------------------------- Clientes (adquirientes)
CREATE TABLE customers (
    tenant_id    uuid         NOT NULL REFERENCES organizations (id),
    id           uuid         NOT NULL,
    id_type      varchar(30)  NOT NULL,
    id_number    varchar(20)  NOT NULL,
    check_digit  smallint,
    legal_name   varchar(450) NOT NULL,
    email        varchar(254),
    phone        varchar(30),
    address      varchar(300),
    city_code    varchar(10),
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    version      integer      NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT customers_identification_uk UNIQUE (tenant_id, id_type, id_number),
    CONSTRAINT customers_nit_dv CHECK (id_type <> 'NIT' OR check_digit IS NOT NULL)
);
CREATE INDEX customers_name_idx ON customers (tenant_id, lower(legal_name));
SELECT app_enable_tenant_rls('customers');

-- ---------------------------------------------------------------- Productos y servicios
CREATE TABLE products (
    tenant_id       uuid          NOT NULL REFERENCES organizations (id),
    id              uuid          NOT NULL,
    sku             varchar(50),
    name            varchar(300)  NOT NULL,
    kind            varchar(10)   NOT NULL CHECK (kind IN ('PRODUCT', 'SERVICE')),
    unit_code       varchar(10)   NOT NULL,
    unit_price      numeric(18,6) NOT NULL CHECK (unit_price >= 0),
    tax_rate_codes  text[]        NOT NULL DEFAULT '{}',
    active          boolean       NOT NULL DEFAULT true,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         integer       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX products_sku_uk ON products (tenant_id, sku) WHERE sku IS NOT NULL;
CREATE INDEX products_name_idx ON products (tenant_id, lower(name));
SELECT app_enable_tenant_rls('products');

GRANT SELECT ON tax_rates TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON customers, products TO ${app_role};
