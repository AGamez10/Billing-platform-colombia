-- Notas crédito (ADR-0028): corrigen una factura validada. Se guardan en invoices con
-- document_kind = 'CREDIT_NOTE', la referencia a la factura (copiada: número, CUFE y fecha no
-- cambian) y el motivo. Cada línea indica qué línea de la factura acredita.
ALTER TABLE invoices DROP CONSTRAINT invoices_document_kind_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_document_kind_check
    CHECK (document_kind IN ('INVOICE', 'POS_EQUIVALENT', 'CREDIT_NOTE'));

ALTER TABLE invoices
    ADD COLUMN credited_invoice_id    uuid,
    ADD COLUMN correction_concept     varchar(20) CHECK (correction_concept IN ('RETURN', 'ANNULMENT')),
    ADD COLUMN correction_description varchar(500),
    ADD COLUMN credited_number        varchar(30),
    ADD COLUMN credited_document_key  varchar(200),
    ADD COLUMN credited_issue_date    date,
    ADD CONSTRAINT invoices_credited_fk FOREIGN KEY (tenant_id, credited_invoice_id) REFERENCES invoices (tenant_id, id),
    ADD CONSTRAINT invoices_credit_note_reference CHECK (
        (document_kind = 'CREDIT_NOTE') = (credited_invoice_id IS NOT NULL)
        AND (credited_invoice_id IS NULL) = (correction_concept IS NULL)
        AND (credited_invoice_id IS NULL) = (correction_description IS NULL)
        AND (credited_invoice_id IS NULL) = (credited_number IS NULL)
        AND (credited_invoice_id IS NULL) = (credited_document_key IS NULL)
        AND (credited_invoice_id IS NULL) = (credited_issue_date IS NULL)),
    ADD CONSTRAINT invoices_not_self_credited CHECK (credited_invoice_id IS DISTINCT FROM id);
CREATE INDEX invoices_credited_idx ON invoices (tenant_id, credited_invoice_id) WHERE credited_invoice_id IS NOT NULL;

ALTER TABLE invoice_lines ADD COLUMN credited_line_number integer CHECK (credited_line_number > 0);

-- La referencia y el motivo tampoco cambian una vez numerada la nota.
CREATE OR REPLACE FUNCTION invoices_guard_credit_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
           NEW.credited_invoice_id IS DISTINCT FROM OLD.credited_invoice_id
        OR NEW.correction_concept IS DISTINCT FROM OLD.correction_concept
        OR NEW.correction_description IS DISTINCT FROM OLD.correction_description
        OR NEW.credited_document_key IS DISTINCT FROM OLD.credited_document_key) THEN
        RAISE EXCEPTION 'the reference of a finalized credit note is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invoices_guard_credit_reference BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard_credit_reference();
