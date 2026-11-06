-- =====================================================================================
-- V8 — Datos necesarios para reconstruir la factura desde la base
-- La línea guarda lo que el usuario definió (descuento y códigos de tarifa) además de los
-- valores calculados; al leerla, el dominio recalcula y verifica que coincidan.
-- =====================================================================================

ALTER TABLE invoice_lines
    ADD COLUMN discount_kind  varchar(10)   NOT NULL DEFAULT 'NONE' CHECK (discount_kind IN ('NONE', 'PERCENT', 'AMOUNT')),
    ADD COLUMN discount_value numeric(18,6) NOT NULL DEFAULT 0 CHECK (discount_value >= 0),
    ADD COLUMN tax_rate_codes text[]        NOT NULL DEFAULT '{}';

-- Ambiente en que se emitió (fijado al finalizar con la cuenta de proveedor).
ALTER TABLE invoices
    ADD COLUMN environment varchar(20) CHECK (environment IN ('SIMULATION', 'HABILITATION', 'PRODUCTION')),
    ADD CONSTRAINT invoices_environment_when_numbered CHECK (status = 'DRAFT' OR environment IS NOT NULL);

-- La lista de facturas filtra por estado y fecha.
CREATE INDEX invoices_status_listing_idx ON invoices (tenant_id, status, issue_date DESC, id);
