-- =====================================================================================
-- V3 — Numeración y facturas
-- Las FK son compuestas (tenant_id, id): es imposible, a nivel de base de datos, que
-- una línea o un historial de un tenant apunte a una factura de otro tenant.
-- =====================================================================================

CREATE TABLE numbering_ranges (
    tenant_id          uuid         NOT NULL REFERENCES organizations (id),
    id                 uuid         NOT NULL,
    document_kind      varchar(20)  NOT NULL CHECK (document_kind IN ('INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE')),
    environment        varchar(20)  NOT NULL CHECK (environment IN ('SIMULATION', 'HABILITATION', 'PRODUCTION')),
    resolution_number  varchar(50)  NOT NULL,
    prefix             varchar(10)  NOT NULL CHECK (prefix ~ '^[A-Z0-9]*$'),
    range_from         bigint       NOT NULL CHECK (range_from > 0),
    range_to           bigint       NOT NULL,
    valid_from         date         NOT NULL,
    valid_to           date         NOT NULL,
    next_number        bigint       NOT NULL,
    active             boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT numbering_ranges_range CHECK (range_to >= range_from),
    CONSTRAINT numbering_ranges_validity CHECK (valid_to >= valid_from),
    CONSTRAINT numbering_ranges_next CHECK (next_number BETWEEN range_from AND range_to + 1)
);
SELECT app_enable_tenant_rls('numbering_ranges');

-- Asignación atómica de número: el UPDATE bloquea la fila; dos transacciones concurrentes
-- se serializan y nunca obtienen el mismo número. NULL si la resolución no está vigente
-- o el rango se agotó (la aplicación traduce a un error de negocio).
CREATE FUNCTION allocate_document_number(p_range_id uuid, p_issue_date date)
    RETURNS TABLE (prefix varchar, number bigint)
    LANGUAGE sql
AS $$
    UPDATE numbering_ranges r
       SET next_number = r.next_number + 1
     WHERE r.id = p_range_id
       AND r.tenant_id = app_current_tenant()
       AND r.active
       AND p_issue_date BETWEEN r.valid_from AND r.valid_to
       AND r.next_number <= r.range_to
    RETURNING r.prefix, r.next_number - 1
$$;

CREATE TABLE invoices (
    tenant_id               uuid          NOT NULL REFERENCES organizations (id),
    id                      uuid          NOT NULL,
    status                  varchar(20)   NOT NULL CHECK (status IN
                                ('DRAFT', 'PENDING_SUBMISSION', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'FAILED')),
    customer_id             uuid,
    customer_snapshot       jsonb         NOT NULL,
    currency                char(3)       NOT NULL DEFAULT 'COP',
    issue_date              date          NOT NULL,
    numbering_range_id      uuid,
    prefix                  varchar(10),
    number                  bigint,
    line_extension_amount   numeric(18,2) NOT NULL,
    tax_total               numeric(18,2) NOT NULL,
    payable_amount          numeric(18,2) NOT NULL,
    provider_account_id     uuid,
    external_reference      varchar(200),
    document_key            varchar(200),           -- CUFE informado por el proveedor
    validation_environment  varchar(20) CHECK (validation_environment IN ('SIMULATION', 'HABILITATION', 'PRODUCTION')),
    validated_at            timestamptz,
    outcome_uncertain       boolean       NOT NULL DEFAULT false,
    provider_interactions   integer       NOT NULL DEFAULT 0,
    last_error              varchar(1000),
    rejection_reasons       jsonb         NOT NULL DEFAULT '[]',
    created_by              uuid          REFERENCES users (id),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    version                 integer       NOT NULL DEFAULT 0,      -- bloqueo optimista
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
    FOREIGN KEY (tenant_id, numbering_range_id) REFERENCES numbering_ranges (tenant_id, id),
    -- Un borrador no tiene número; cualquier otro estado sí.
    CONSTRAINT invoices_number_by_status CHECK (
        (status = 'DRAFT' AND number IS NULL AND prefix IS NULL)
        OR (status <> 'DRAFT' AND number IS NOT NULL AND prefix IS NOT NULL AND numbering_range_id IS NOT NULL)),
    -- No hay validación sin evidencia (misma invariante que el dominio, garantizada también aquí).
    CONSTRAINT invoices_accepted_has_evidence CHECK (
        status <> 'ACCEPTED'
        OR (document_key IS NOT NULL AND validation_environment IS NOT NULL AND validated_at IS NOT NULL)),
    CONSTRAINT invoices_totals CHECK (payable_amount = line_extension_amount + tax_total),
    CONSTRAINT invoices_currency CHECK (currency = 'COP')
);
CREATE UNIQUE INDEX invoices_number_uk ON invoices (tenant_id, prefix, number) WHERE number IS NOT NULL;
CREATE UNIQUE INDEX invoices_external_ref_uk ON invoices (tenant_id, provider_account_id, external_reference)
    WHERE external_reference IS NOT NULL;
CREATE INDEX invoices_listing_idx ON invoices (tenant_id, issue_date DESC, id);
CREATE INDEX invoices_status_idx ON invoices (tenant_id, status) WHERE status IN ('PENDING_SUBMISSION', 'SUBMITTED');
SELECT app_enable_tenant_rls('invoices');

CREATE TABLE invoice_lines (
    tenant_id        uuid          NOT NULL,
    invoice_id       uuid          NOT NULL,
    line_number      integer       NOT NULL CHECK (line_number > 0),
    product_id       uuid,
    description      varchar(300)  NOT NULL,
    unit_code        varchar(10)   NOT NULL,
    quantity         numeric(18,6) NOT NULL CHECK (quantity > 0),
    unit_price       numeric(18,6) NOT NULL CHECK (unit_price >= 0),
    gross_amount     numeric(18,2) NOT NULL,
    discount_amount  numeric(18,2) NOT NULL CHECK (discount_amount >= 0),
    taxable_amount   numeric(18,2) NOT NULL,
    tax_total        numeric(18,2) NOT NULL,
    line_total       numeric(18,2) NOT NULL,
    taxes            jsonb         NOT NULL,     -- [{code,type,treatment,percentage,taxable,amount}]
    PRIMARY KEY (tenant_id, invoice_id, line_number),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT invoice_lines_math CHECK (
        gross_amount - discount_amount = taxable_amount AND taxable_amount + tax_total = line_total)
);
SELECT app_enable_tenant_rls('invoice_lines');

CREATE TABLE invoice_status_history (
    tenant_id    uuid         NOT NULL,
    invoice_id   uuid         NOT NULL,
    seq          integer      NOT NULL,
    from_status  varchar(20),
    to_status    varchar(20)  NOT NULL,
    occurred_at  timestamptz  NOT NULL,
    detail       varchar(1000),
    PRIMARY KEY (tenant_id, invoice_id, seq),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id) ON DELETE CASCADE
);
SELECT app_enable_tenant_rls('invoice_status_history');

-- Borrar solo es posible para borradores (no tienen número). Lo demás es inmutable salvo estado.
CREATE FUNCTION invoices_guard_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'only draft invoices can be deleted' USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER invoices_guard_delete BEFORE DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard_delete();

-- Una factura numerada no cambia su contenido económico ni su numeración.
CREATE FUNCTION invoices_guard_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
           NEW.number IS DISTINCT FROM OLD.number OR NEW.prefix IS DISTINCT FROM OLD.prefix
        OR NEW.payable_amount IS DISTINCT FROM OLD.payable_amount
        OR NEW.tax_total IS DISTINCT FROM OLD.tax_total
        OR NEW.customer_snapshot IS DISTINCT FROM OLD.customer_snapshot
        OR NEW.issue_date IS DISTINCT FROM OLD.issue_date) THEN
        RAISE EXCEPTION 'finalized invoices are immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('ACCEPTED', 'REJECTED') AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'final invoice status cannot change' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invoices_guard_update BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard_update();

-- Las líneas solo cambian mientras la factura es borrador. Al finalizar, la aplicación
-- reescribe las líneas recalculadas ANTES de cambiar el estado de la factura.
CREATE FUNCTION invoice_lines_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_status varchar;
    row_ref invoice_lines;
BEGIN
    row_ref := COALESCE(NEW, OLD);
    SELECT status INTO parent_status FROM invoices
     WHERE tenant_id = row_ref.tenant_id AND id = row_ref.invoice_id;
    -- Sin padre visible: borrado en cascada de un borrador (ya validado por invoices_guard_delete).
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'lines of a finalized invoice are immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER invoice_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW EXECUTE FUNCTION invoice_lines_guard();

GRANT SELECT, INSERT, UPDATE ON numbering_ranges TO ${app_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON invoices, invoice_lines TO ${app_role};
GRANT SELECT, INSERT ON invoice_status_history TO ${app_role};
GRANT EXECUTE ON FUNCTION allocate_document_number(uuid, date) TO ${app_role};
