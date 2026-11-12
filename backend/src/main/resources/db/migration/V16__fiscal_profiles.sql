-- Perfil tributario y dirección fiscal del emisor (organizations) y del adquiriente (customers).
--
-- Códigos verificados el 2026-11-12: responsabilidades del anexo DIAN "Reglas activas 1 agosto
-- 2020" (O-13, O-15, O-23, O-47, R-99-PN); tipo de persona (1 jurídica, 2 natural) y régimen
-- (48 responsable de IVA, 49 no responsable) según la documentación de Muisca/Aliaddo. Aquí se
-- guardan como nombres del dominio; el código DIAN lo pone cada adaptador.
--
-- Los datos son opcionales para filas existentes: la aplicación los exige antes de emitir en
-- habilitación o producción (no en simulación).

ALTER TABLE organizations
    ADD COLUMN phone                varchar(30),
    ADD COLUMN address_line         varchar(300),
    ADD COLUMN city_code            char(5),
    ADD COLUMN person_type          varchar(10),
    ADD COLUMN vat_regime           varchar(20),
    ADD COLUMN tax_responsibilities varchar(60),
    ADD COLUMN updated_at           timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT organizations_person_type CHECK (person_type IN ('LEGAL', 'NATURAL')),
    ADD CONSTRAINT organizations_vat_regime CHECK (vat_regime IN ('RESPONSIBLE', 'NOT_RESPONSIBLE')),
    ADD CONSTRAINT organizations_responsibilities
        CHECK (tax_responsibilities ~ '^(R-99-PN|(O-13|O-15|O-23|O-47)(,(O-13|O-15|O-23|O-47)){0,3})$'),
    -- El perfil va completo o no va; igual la dirección (línea y municipio).
    ADD CONSTRAINT organizations_fiscal_complete
        CHECK ((person_type IS NULL) = (vat_regime IS NULL) AND (vat_regime IS NULL) = (tax_responsibilities IS NULL)),
    ADD CONSTRAINT organizations_address_complete CHECK ((address_line IS NULL) = (city_code IS NULL)),
    ADD CONSTRAINT organizations_city_code CHECK (city_code ~ '^[0-9]{5}$');

ALTER TABLE customers
    ADD COLUMN person_type          varchar(10),
    ADD COLUMN vat_regime           varchar(20),
    ADD COLUMN tax_responsibilities varchar(60),
    ADD CONSTRAINT customers_person_type CHECK (person_type IN ('LEGAL', 'NATURAL')),
    ADD CONSTRAINT customers_vat_regime CHECK (vat_regime IN ('RESPONSIBLE', 'NOT_RESPONSIBLE')),
    ADD CONSTRAINT customers_responsibilities
        CHECK (tax_responsibilities ~ '^(R-99-PN|(O-13|O-15|O-23|O-47)(,(O-13|O-15|O-23|O-47)){0,3})$'),
    ADD CONSTRAINT customers_fiscal_complete
        CHECK ((person_type IS NULL) = (vat_regime IS NULL) AND (vat_regime IS NULL) = (tax_responsibilities IS NULL));

-- La aplicación solo puede cambiar los datos editables de su organización: nunca la
-- identificación (NIT), el estado ni la fecha de creación.
REVOKE UPDATE ON organizations FROM ${app_role};
GRANT UPDATE (legal_name, trade_name, email, phone, address_line, city_code, person_type, vat_regime,
              tax_responsibilities, updated_at) ON organizations TO ${app_role};
