-- Planes de comercio (aprobados por el propietario el 2026-11-06, docs/PRICING.md).
-- Se cobran por bloques de documentos porque un comercio emite un POS por venta.
-- Pago anual: 11 meses (no 10) porque el margen por documento es menor.
ALTER TABLE plans
    ADD COLUMN family varchar(20) NOT NULL DEFAULT 'GENERAL' CHECK (family IN ('TRIAL', 'GENERAL', 'COMMERCE'));
UPDATE plans SET family = 'TRIAL' WHERE code = 'TRIAL';

INSERT INTO plans (code, name, quota_documents, quota_scope, max_users, monthly_price_cop, annual_price_cop,
                   monthly_billing_allowed, overage_unit_price_cop, public, active, family) VALUES
    ('COMERCIO_500',  'Comercio 500',    500, 'MONTHLY', NULL,  34900,  383900.00, true, 60, true, true, 'COMMERCE'),
    ('COMERCIO_1200', 'Comercio 1.200', 1200, 'MONTHLY', NULL,  64900,  713900.00, true, 60, true, true, 'COMMERCE'),
    ('COMERCIO_3000', 'Comercio 3.000', 3000, 'MONTHLY', NULL, 139900, 1538900.00, true, 60, true, true, 'COMMERCE');
