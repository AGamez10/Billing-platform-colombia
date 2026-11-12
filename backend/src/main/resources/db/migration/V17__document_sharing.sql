-- Representación del documento y envío al comprador.
--
-- qr_payload: contenido del código QR tal como lo entregó el proveedor tecnológico (lo calcula
-- él según el Anexo Técnico vigente; la plataforma no lo inventa). NULL si el proveedor no lo
-- informa. Se fija al validarse y no cambia después.
ALTER TABLE invoices
    ADD COLUMN qr_payload text CHECK (char_length(qr_payload) <= 2000);

CREATE OR REPLACE FUNCTION invoices_guard_qr() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.qr_payload IS NOT NULL AND NEW.qr_payload IS DISTINCT FROM OLD.qr_payload THEN
        RAISE EXCEPTION 'the QR of a validated document cannot change' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER invoices_guard_qr BEFORE UPDATE OF qr_payload ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoices_guard_qr();

-- Enlaces para que el comprador vea su documento sin cuenta. Solo se guarda el SHA-256 del
-- token (256 bits aleatorios); el token viaja en el fragmento de la URL.
CREATE TABLE invoice_share_links (
    tenant_id   uuid        NOT NULL REFERENCES organizations (id),
    id          uuid        NOT NULL,
    invoice_id  uuid        NOT NULL,
    token_hash  bytea       NOT NULL CHECK (octet_length(token_hash) = 32),
    channel     varchar(10) NOT NULL CHECK (channel IN ('EMAIL', 'LINK')),
    sent_to     varchar(254),
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id),
    CHECK (expires_at > created_at),
    CHECK ((channel = 'EMAIL') = (sent_to IS NOT NULL))
);
CREATE UNIQUE INDEX invoice_share_links_token_uk ON invoice_share_links (token_hash);
CREATE INDEX invoice_share_links_invoice_idx ON invoice_share_links (tenant_id, invoice_id, created_at DESC);
SELECT app_enable_tenant_rls('invoice_share_links');
GRANT SELECT, INSERT ON invoice_share_links TO ${app_role};
GRANT UPDATE (revoked_at) ON invoice_share_links TO ${app_role};

-- Sin tenant en contexto: a qué documento lleva un enlace vigente. Solo por hash exacto.
CREATE FUNCTION invoice_share_find(p_token_hash bytea)
    RETURNS TABLE (tenant_id uuid, invoice_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT l.tenant_id, l.invoice_id
    FROM invoice_share_links l JOIN organizations o ON o.id = l.tenant_id
    WHERE l.token_hash = p_token_hash AND l.revoked_at IS NULL AND l.expires_at > now() AND o.status = 'ACTIVE'
$$;
REVOKE ALL ON FUNCTION invoice_share_find(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION invoice_share_find(bytea) TO ${app_role};
