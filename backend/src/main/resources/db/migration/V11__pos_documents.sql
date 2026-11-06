-- Documento equivalente electrónico POS (Resolución DIAN 000165 de 2023): mismo ciclo de vida
-- que la factura, con resolución de numeración propia.
ALTER TABLE invoices
    ADD COLUMN document_kind varchar(20) NOT NULL DEFAULT 'INVOICE'
        CHECK (document_kind IN ('INVOICE', 'POS_EQUIVALENT'));

ALTER TABLE numbering_ranges DROP CONSTRAINT numbering_ranges_document_kind_check;
ALTER TABLE numbering_ranges ADD CONSTRAINT numbering_ranges_document_kind_check
    CHECK (document_kind IN ('INVOICE', 'POS_EQUIVALENT', 'CREDIT_NOTE', 'DEBIT_NOTE'));

-- El número es único por tipo de documento y ambiente: practicar en simulación con el prefijo
-- de la resolución real no debe bloquear después el mismo número en producción.
DROP INDEX invoices_number_uk;
CREATE UNIQUE INDEX invoices_number_uk ON invoices (tenant_id, document_kind, environment, prefix, number)
    WHERE number IS NOT NULL;

CREATE INDEX invoices_kind_listing_idx ON invoices (tenant_id, document_kind, issue_date DESC, id);
