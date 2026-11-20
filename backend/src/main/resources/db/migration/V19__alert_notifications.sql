-- Avisos automáticos por correo (ADR-0030). Cada pendiente tiene una clave estable; se registra
-- la que ya se avisó para no repetirla. Los dueños pueden apagar los correos de su organización.
ALTER TABLE organizations ADD COLUMN email_alerts boolean NOT NULL DEFAULT true;
GRANT UPDATE (email_alerts) ON organizations TO ${app_role};

CREATE TABLE alert_notifications (
    tenant_id   uuid         NOT NULL REFERENCES organizations (id),
    alert_key   varchar(200) NOT NULL,
    code        varchar(40)  NOT NULL,
    recipients  integer      NOT NULL CHECK (recipients >= 0),
    sent_at     timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, alert_key)
);
CREATE INDEX alert_notifications_sent_idx ON alert_notifications (sent_at);
SELECT app_enable_tenant_rls('alert_notifications');
GRANT SELECT, INSERT ON alert_notifications TO ${app_role};

-- Sin tenant en contexto: organizaciones activas con avisos encendidos (solo sus ids).
CREATE FUNCTION organizations_with_email_alerts()
    RETURNS TABLE (tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$
    SELECT id FROM organizations WHERE status = 'ACTIVE' AND email_alerts ORDER BY id
$$;
REVOKE ALL ON FUNCTION organizations_with_email_alerts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION organizations_with_email_alerts() TO ${app_role};
