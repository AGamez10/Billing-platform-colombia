-- =====================================================================================
-- Pruebas de aislamiento multi-tenant e invariantes de base de datos.
-- Se ejecutan contra PostgreSQL real con las migraciones aplicadas (ver
-- backend/src/test/sql/run.sh). Las aserciones se hacen como billing_app: el mismo rol,
-- sin BYPASSRLS, que usa la aplicación en ejecución.
-- Cualquier aserción fallida aborta el script con código de salida distinto de cero.
-- =====================================================================================
\set ON_ERROR_STOP on
\set QUIET on
SET client_min_messages = notice;

\set tenant_a '''aaaaaaaa-0000-0000-0000-000000000001'''
\set tenant_b '''bbbbbbbb-0000-0000-0000-000000000002'''

-- ---------------------------------------------------------------- utilidades de prueba
CREATE SCHEMA t;
GRANT USAGE ON SCHEMA t TO billing_app;

CREATE FUNCTION t.expect_sqlstate(p_sql text, p_state text, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = p_state THEN
            RAISE NOTICE 'PASS  %', p_name;
            RETURN;
        END IF;
        RAISE EXCEPTION 'FAIL  %: expected SQLSTATE % but got % (%)', p_name, p_state, SQLSTATE, SQLERRM;
    END;
    RAISE EXCEPTION 'FAIL  %: expected SQLSTATE % but the statement succeeded', p_name, p_state;
END $$;

CREATE FUNCTION t.expect_eq(p_actual bigint, p_expected bigint, p_name text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_actual IS DISTINCT FROM p_expected THEN
        RAISE EXCEPTION 'FAIL  %: expected % but was %', p_name, p_expected, p_actual;
    END IF;
    RAISE NOTICE 'PASS  %', p_name;
END $$;

CREATE FUNCTION t.rows_affected(p_sql text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
    EXECUTE p_sql;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA t TO billing_app;

-- ---------------------------------------------------------------- datos (como superusuario)
INSERT INTO organizations (id, legal_name, id_type, id_number, check_digit) VALUES
    (:tenant_a, 'Fundación A', 'NIT', '900000001', 1),
    (:tenant_b, 'Comercio B',  'NIT', '900000002', 2);

INSERT INTO users (id, email, password_hash, full_name) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'ana@a.test',   '$argon2id$placeholder', 'Ana'),
    ('00000000-0000-0000-0000-0000000000b1', 'beto@b.test',  '$argon2id$placeholder', 'Beto');
INSERT INTO memberships (tenant_id, user_id, role) VALUES
    (:tenant_a, '00000000-0000-0000-0000-0000000000a1', 'OWNER'),
    (:tenant_b, '00000000-0000-0000-0000-0000000000b1', 'OWNER');

INSERT INTO customers (tenant_id, id, id_type, id_number, legal_name) VALUES
    (:tenant_a, 'c0000000-0000-0000-0000-0000000000a1', 'CEDULA_CIUDADANIA', '111', 'Cliente A1'),
    (:tenant_a, 'c0000000-0000-0000-0000-0000000000a2', 'CEDULA_CIUDADANIA', '222', 'Cliente A2'),
    (:tenant_b, 'c0000000-0000-0000-0000-0000000000b1', 'CEDULA_CIUDADANIA', '111', 'Cliente B1');

INSERT INTO numbering_ranges (tenant_id, id, document_kind, environment, resolution_number, prefix,
                              range_from, range_to, valid_from, valid_to, next_number) VALUES
    (:tenant_a, 'e0000000-0000-0000-0000-0000000000a1', 'INVOICE', 'HABILITATION', 'R-A', 'SETP', 1, 3, '2026-01-01', '2027-12-31', 2),
    (:tenant_b, 'e0000000-0000-0000-0000-0000000000b1', 'INVOICE', 'HABILITATION', 'R-B', 'SETP', 1, 100, '2026-01-01', '2027-12-31', 2);

INSERT INTO invoices (tenant_id, id, status, customer_id, customer_snapshot, issue_date, numbering_range_id,
                      prefix, number, line_extension_amount, tax_total, payable_amount) VALUES
    (:tenant_a, 'f0000000-0000-0000-0000-0000000000a1', 'DRAFT', 'c0000000-0000-0000-0000-0000000000a1', '{}', '2026-11-05', NULL, NULL, NULL, 100, 19, 119),
    (:tenant_a, 'f0000000-0000-0000-0000-0000000000a2', 'DRAFT', 'c0000000-0000-0000-0000-0000000000a1', '{}', '2026-11-05', NULL, NULL, NULL, 100, 19, 119),
    (:tenant_b, 'f0000000-0000-0000-0000-0000000000b1', 'DRAFT', 'c0000000-0000-0000-0000-0000000000b1', '{}', '2026-11-05', NULL, NULL, NULL, 200, 38, 238);

-- Mismo orden que la aplicación: líneas mientras es borrador, luego se finaliza.
INSERT INTO invoice_lines (tenant_id, invoice_id, line_number, description, unit_code, quantity, unit_price,
                           gross_amount, discount_amount, taxable_amount, tax_total, line_total, taxes) VALUES
    (:tenant_a, 'f0000000-0000-0000-0000-0000000000a1', 1, 'Item', '94', 1, 100, 100, 0, 100, 19, 119, '[]'),
    (:tenant_a, 'f0000000-0000-0000-0000-0000000000a2', 1, 'Item', '94', 1, 100, 100, 0, 100, 19, 119, '[]');

UPDATE invoices SET status = 'PENDING_SUBMISSION', prefix = 'SETP', number = 1, environment = 'HABILITATION',
       numbering_range_id = CASE tenant_id WHEN :tenant_a THEN 'e0000000-0000-0000-0000-0000000000a1'::uuid
                                           ELSE 'e0000000-0000-0000-0000-0000000000b1'::uuid END
 WHERE id IN ('f0000000-0000-0000-0000-0000000000a2', 'f0000000-0000-0000-0000-0000000000b1');

INSERT INTO submission_jobs (tenant_id, id, invoice_id) VALUES
    (:tenant_a, '10000000-0000-0000-0000-0000000000a2', 'f0000000-0000-0000-0000-0000000000a2'),
    (:tenant_b, '10000000-0000-0000-0000-0000000000b1', 'f0000000-0000-0000-0000-0000000000b1');

INSERT INTO api_keys (tenant_id, id, name, key_prefix, key_hash, scopes) VALUES
    (:tenant_a, '20000000-0000-0000-0000-0000000000a1', 'AdoptaFacil', 'bpk_a1b2c3d4', '\x00', '{invoices:write}');

INSERT INTO audit_log (tenant_id, action, resource_type) VALUES (:tenant_a, 'INVOICE_FINALIZED', 'invoice');

INSERT INTO refresh_tokens (id, user_id, tenant_id, family_id, token_hash, expires_at) VALUES
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', :tenant_a, gen_random_uuid(), '\xa1', now() + interval '7 days'),
    (gen_random_uuid(), '00000000-0000-0000-0000-0000000000b1', :tenant_b, gen_random_uuid(), '\xb1', now() + interval '7 days');

\echo '== RLS como billing_app =='
SET ROLE billing_app;

-- 1. Sin tenant en contexto no se ve nada (fail-closed).
SELECT t.expect_eq((SELECT count(*) FROM customers), 0, 'sin tenant: 0 clientes visibles');
SELECT t.expect_eq((SELECT count(*) FROM invoices), 0, 'sin tenant: 0 facturas visibles');
SELECT t.expect_eq((SELECT count(*) FROM organizations), 0, 'sin tenant: 0 organizaciones visibles');

-- 2. Con tenant A solo se ven datos de A.
SELECT set_config('app.tenant_id', :tenant_a, false);
SELECT t.expect_eq((SELECT count(*) FROM customers), 2, 'tenant A ve solo sus 2 clientes');
SELECT t.expect_eq((SELECT count(*) FROM invoices), 2, 'tenant A ve solo sus 2 facturas');
SELECT t.expect_eq((SELECT count(*) FROM organizations), 1, 'tenant A ve solo su organización');
SELECT t.expect_eq((SELECT count(*) FROM memberships), 1, 'tenant A ve solo sus membresías');
SELECT t.expect_eq((SELECT count(*) FROM submission_jobs), 1, 'tenant A ve solo sus envíos');

-- 3. IDOR/BOLA: conocer el id de un recurso de B no da acceso.
SELECT t.expect_eq((SELECT count(*) FROM invoices WHERE id = 'f0000000-0000-0000-0000-0000000000b1'), 0,
                   'IDOR: leer factura de B por id devuelve 0 filas');
SELECT t.expect_eq(t.rows_affected($$UPDATE customers SET legal_name = 'pwned' WHERE id = 'c0000000-0000-0000-0000-0000000000b1'$$), 0,
                   'IDOR: actualizar cliente de B afecta 0 filas');
SELECT t.expect_eq(t.rows_affected($$DELETE FROM invoices WHERE id = 'f0000000-0000-0000-0000-0000000000b1'$$), 0,
                   'IDOR: borrar factura de B afecta 0 filas');

-- 4. No se puede escribir en otro tenant ni mover filas hacia él.
SELECT t.expect_sqlstate(
    $$INSERT INTO customers (tenant_id, id, id_type, id_number, legal_name)
      VALUES ('bbbbbbbb-0000-0000-0000-000000000002', gen_random_uuid(), 'CEDULA_CIUDADANIA', '999', 'Intruso')$$,
    '42501', 'insertar con tenant_id de B es rechazado por RLS');
SELECT t.expect_sqlstate(
    $$UPDATE customers SET tenant_id = 'bbbbbbbb-0000-0000-0000-000000000002' WHERE id = 'c0000000-0000-0000-0000-0000000000a2'$$,
    '42501', 'mover un cliente de A hacia B es rechazado por RLS');
SELECT t.expect_sqlstate(
    $$INSERT INTO memberships (tenant_id, user_id, role)
      VALUES ('bbbbbbbb-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1', 'OWNER')$$,
    '42501', 'escalada: A no puede darse membresía en B');

-- 5. FK compuestas: una línea de A no puede colgar de una factura de B.
SELECT t.expect_sqlstate(
    $$INSERT INTO invoice_lines (tenant_id, invoice_id, line_number, description, unit_code, quantity, unit_price,
                                 gross_amount, discount_amount, taxable_amount, tax_total, line_total, taxes)
      VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-0000000000b1', 9, 'x', '94', 1, 1, 1, 0, 1, 0, 1, '[]')$$,
    '23503', 'línea de A apuntando a factura de B viola la FK compuesta');

-- 6. Numeración atómica, acotada al tenant y al rango autorizado.
SELECT t.expect_eq((SELECT number FROM allocate_document_number('e0000000-0000-0000-0000-0000000000a1', '2026-11-05')), 2,
                   'asigna el siguiente número (2)');
SELECT t.expect_eq((SELECT number FROM allocate_document_number('e0000000-0000-0000-0000-0000000000a1', '2026-11-05')), 3,
                   'asigna el siguiente número (3)');
SELECT t.expect_eq((SELECT count(*) FROM allocate_document_number('e0000000-0000-0000-0000-0000000000a1', '2026-11-05')), 0,
                   'rango agotado: no asigna número');
SELECT t.expect_eq((SELECT count(*) FROM allocate_document_number('e0000000-0000-0000-0000-0000000000b1', '2026-11-05')), 0,
                   'no se puede consumir la numeración de B');
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET status = 'DRAFT', prefix = NULL, number = 1 WHERE id = 'f0000000-0000-0000-0000-0000000000a1'$$,
    '23514', 'borrador con número viola la restricción de numeración');

-- 7. Invariantes documentales.
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET document_kind = 'CREDIT_NOTE' WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'una venta es factura o POS, nada más');
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET environment = NULL WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'una factura numerada siempre conserva el ambiente en que se emitió');
SELECT t.expect_sqlstate(
    $$UPDATE invoice_status_history SET detail = 'x'$$,
    '42501', 'el historial de estados no se modifica desde la aplicación');
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET status = 'ACCEPTED' WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'ACCEPTED sin evidencia (CUFE/ambiente) es rechazado');
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET payable_amount = 1, tax_total = 0, line_extension_amount = 1 WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'factura numerada no puede cambiar montos');
SELECT t.expect_sqlstate(
    $$UPDATE invoice_lines SET description = 'cambiada' WHERE invoice_id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'líneas de factura numerada son inmutables');
SELECT t.expect_sqlstate(
    $$DELETE FROM invoices WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'factura numerada no se puede borrar');
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET line_extension_amount = 1 WHERE id = 'f0000000-0000-0000-0000-0000000000a1'$$,
    '23514', 'totales inconsistentes son rechazados');
SELECT t.expect_eq(t.rows_affected($$UPDATE invoice_lines SET description = 'editable' WHERE invoice_id = 'f0000000-0000-0000-0000-0000000000a1'$$), 1,
                   'líneas de un borrador sí son editables');
SELECT t.expect_eq(t.rows_affected($$DELETE FROM invoices WHERE id = 'f0000000-0000-0000-0000-0000000000a1'$$), 1,
                   'un borrador sí se puede borrar (con sus líneas)');
UPDATE invoices SET status = 'ACCEPTED', document_key = 'SIMULATED-x', validation_environment = 'SIMULATION', validated_at = now()
 WHERE id = 'f0000000-0000-0000-0000-0000000000a2';
SELECT t.expect_sqlstate(
    $$UPDATE invoices SET status = 'REJECTED' WHERE id = 'f0000000-0000-0000-0000-0000000000a2'$$,
    '23514', 'el estado final ACCEPTED no puede cambiar');

-- 8. Auditoría de solo inserción y privilegios mínimos.
SELECT t.expect_sqlstate($$UPDATE audit_log SET action = 'x'$$, '42501', 'auditoría: UPDATE denegado');
SELECT t.expect_sqlstate($$DELETE FROM audit_log$$, '42501', 'auditoría: DELETE denegado');
SELECT t.expect_sqlstate($$DELETE FROM customers WHERE true$$, '42501', 'clientes no se borran (se desactivan)');
SELECT t.expect_sqlstate($$INSERT INTO tax_rates VALUES ('X', 'IVA', 'TAXED', 1, '2026-01-01', NULL, 'x', '2026-01-01')$$,
                         '42501', 'la aplicación no modifica el catálogo tributario');
SELECT t.expect_sqlstate($$ALTER TABLE invoices DISABLE ROW LEVEL SECURITY$$, '42501', 'la aplicación no puede desactivar RLS');

-- 9. Flujos sin tenant en contexto, a través de funciones acotadas.
SELECT set_config('app.tenant_id', '', false);
SELECT t.expect_eq((SELECT count(*) FROM auth_find_api_key('bpk_a1b2c3d4')), 1, 'auth por API key resuelve sin tenant');
SELECT t.expect_eq((SELECT count(*) FROM auth_find_api_key('bpk_nope')), 0, 'API key inexistente no resuelve');
SELECT t.expect_eq((SELECT count(*) FROM claim_submission_jobs(10, 60)), 2, 'el worker reclama los envíos pendientes de todos los tenants');
SELECT t.expect_eq((SELECT count(*) FROM claim_submission_jobs(10, 60)), 0, 'un envío reclamado no se reclama dos veces');
SELECT set_config('app.user_id', '00000000-0000-0000-0000-0000000000a1', false);
SELECT t.expect_eq((SELECT count(*) FROM memberships), 1, 'un usuario ve solo sus membresías al iniciar sesión');
SELECT t.expect_eq((SELECT count(*) FROM refresh_tokens), 1, 'un usuario ve solo sus refresh tokens');
SELECT t.expect_eq(t.rows_affected($$UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = '\xb1'$$), 0,
                   'un usuario no puede revocar tokens de otro');
SELECT set_config('app.user_id', '', false);
SELECT t.expect_eq((SELECT count(*) FROM refresh_tokens), 0, 'sin usuario en contexto no se ven refresh tokens');
SELECT t.expect_eq((SELECT count(*) FROM auth_find_refresh_token('\xa1')), 1, 'refresh token se resuelve por hash');
SELECT register_organization('cccccccc-0000-0000-0000-000000000003', 'Restaurante C', 'NIT', '800197268', 4::smallint,
                             NULL, '00000000-0000-0000-0000-0000000000c1', 'Carla@C.test', '$argon2id$placeholder', 'Carla');
SELECT set_config('app.tenant_id', 'cccccccc-0000-0000-0000-000000000003', false);
SELECT t.expect_eq((SELECT count(*) FROM memberships WHERE role = 'OWNER'), 1, 'registro crea organización con su dueño');
SELECT t.expect_eq((SELECT count(*) FROM invoices), 0, 'una organización nueva no ve facturas de otras');

-- 10. Planes: visibles para la app, inmodificables por ella.
SELECT t.expect_eq((SELECT count(*) FROM plans WHERE active), 8, 'planes aprobados sembrados (prueba + 4 generales + 3 de comercio)');
SELECT t.expect_eq((SELECT count(*) FROM plans WHERE family = 'COMMERCE' AND overage_unit_price_cop = 60
                    AND annual_price_cop = monthly_price_cop * 11), 3, 'comercio: excedente de $60 y pago anual de 11 meses');
SELECT t.expect_eq((SELECT count(*) FROM plans WHERE code = 'MICRO' AND NOT monthly_billing_allowed AND annual_price_cop = 69000), 1,
                   'Micro solo con pago anual de $69.000');
SELECT t.expect_eq((SELECT count(*) FROM plans WHERE code = 'TRIAL' AND quota_scope = 'TOTAL' AND quota_documents = 20 AND overage_unit_price_cop IS NULL), 1,
                   'prueba gratis: 20 documentos en total, sin excedentes');
SELECT t.expect_sqlstate($$UPDATE plans SET monthly_price_cop = 0$$, '42501', 'la aplicación no modifica precios de planes');


-- 11. Cobros en tienda (V12): cuenta de pasarela del comercio y cobros, aislados por tenant.
SELECT set_config('app.tenant_id', :tenant_a, false);
INSERT INTO provider_secrets (tenant_id, ref, ciphertext, nonce, key_version) VALUES
    (:tenant_a, 'pay:bold:1:identity', '\x01', '\x000000000000000000000001', 1),
    (:tenant_a, 'pay:bold:1:secret',   '\x02', '\x000000000000000000000002', 1);
INSERT INTO payment_accounts (tenant_id, id, gateway, mode, identity_key_ref, secret_key_ref, webhook_token_hash)
VALUES (:tenant_a, 'ac000000-0000-0000-0000-0000000000a1', 'BOLD', 'TEST', 'pay:bold:1:identity', 'pay:bold:1:secret', '\xaa01');
SELECT t.expect_sqlstate(
    $$INSERT INTO payment_accounts (tenant_id, id, gateway, mode, identity_key_ref, secret_key_ref, webhook_token_hash)
      VALUES ('aaaaaaaa-0000-0000-0000-000000000001', gen_random_uuid(), 'BOLD', 'LIVE', 'pay:bold:1:identity', 'pay:bold:1:secret', '\xaa02')$$,
    '23505', 'una sola cuenta de pasarela activa por organización');
SELECT t.expect_sqlstate(
    $$INSERT INTO payment_accounts (tenant_id, id, gateway, mode, identity_key_ref, secret_key_ref, webhook_token_hash, active)
      VALUES ('aaaaaaaa-0000-0000-0000-000000000001', gen_random_uuid(), 'BOLD', 'TEST', 'no-existe', 'pay:bold:1:secret', '\xaa03', false)$$,
    '23503', 'las llaves de la cuenta deben existir en el almacén cifrado');
INSERT INTO charges (tenant_id, id, invoice_id, payment_account_id, created_by, amount, status, created_at, updated_at)
VALUES (:tenant_a, 'c4000000-0000-0000-0000-0000000000a1', 'f0000000-0000-0000-0000-0000000000a2',
        'ac000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', 5950, 'PENDING',
        now() - interval '5 minutes', now() - interval '5 minutes');
SELECT t.expect_sqlstate(
    $$INSERT INTO charges (tenant_id, id, invoice_id, payment_account_id, created_by, amount, status)
      VALUES ('aaaaaaaa-0000-0000-0000-000000000001', gen_random_uuid(), 'f0000000-0000-0000-0000-0000000000a2',
              'ac000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', 5950, 'PENDING')$$,
    '23505', 'una venta no puede tener dos cobros vivos (nunca se cobra dos veces)');
SELECT t.expect_sqlstate(
    $$UPDATE charges SET status = 'PAID' WHERE id = 'c4000000-0000-0000-0000-0000000000a1'$$,
    '23514', 'un cobro pagado exige la fecha de pago');
SELECT t.expect_sqlstate(
    $$INSERT INTO charges (tenant_id, id, invoice_id, payment_account_id, created_by, amount, status)
      VALUES ('aaaaaaaa-0000-0000-0000-000000000001', gen_random_uuid(), 'f0000000-0000-0000-0000-0000000000a2',
              'ac000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1', 0, 'REJECTED')$$,
    '23514', 'un cobro debe ser por un valor positivo');
SELECT t.expect_sqlstate($$DELETE FROM charges$$, '42501', 'los cobros no se borran');
SELECT t.expect_sqlstate($$DELETE FROM payment_accounts$$, '42501', 'las cuentas de pasarela no se borran (se desactivan)');
SELECT set_config('app.tenant_id', :tenant_b, false);
SELECT t.expect_eq((SELECT count(*) FROM payment_accounts), 0, 'B no ve la cuenta de pasarela de A');
SELECT t.expect_eq((SELECT count(*) FROM charges), 0, 'B no ve los cobros de A');
SELECT t.expect_eq(t.rows_affected($$UPDATE charges SET status = 'CANCELLED'$$), 0, 'B no puede alterar cobros de A');
SELECT set_config('app.tenant_id', '', false);
SELECT t.expect_eq((SELECT count(*) FROM payment_webhook_find_account('\xaa01')), 1, 'el webhook ubica la cuenta por el hash del token, sin tenant');
SELECT t.expect_eq((SELECT count(*) FROM payment_webhook_find_account('\xaa99')), 0, 'un token desconocido no ubica nada');
SELECT t.expect_eq((SELECT count(*) FROM claim_pending_charges(10)), 1, 'la conciliación reclama cobros pendientes de cualquier tenant');
SELECT t.expect_eq((SELECT count(*) FROM claim_pending_charges(10)), 0, 'un cobro recién reclamado no se reclama otra vez (lease)');

RESET ROLE;
\echo '== Invariantes del catálogo (como dueño) =='
SELECT t.expect_sqlstate(
    $$INSERT INTO tax_rates VALUES ('IVA_19', 'IVA', 'TAXED', 21, '2026-06-01', NULL, 'test', '2026-11-05')$$,
    '23P01', 'no pueden coexistir dos tarifas vigentes con el mismo código');
SELECT t.expect_sqlstate(
    $$INSERT INTO tax_rates VALUES ('IVA_X', 'IVA', 'EXEMPT', 5, '2026-01-01', NULL, 'test', '2026-11-05')$$,
    '23514', 'una tarifa exenta debe ser 0%');
SELECT t.expect_sqlstate(
    $$INSERT INTO plans (code, name, quota_scope, overage_unit_price_cop, monthly_billing_allowed) VALUES ('X', 'x', 'TOTAL', 150, true)$$,
    '23514', 'una prueba gratis (cuota total) no puede tener excedentes');
SELECT t.expect_eq((SELECT count(*) FROM pg_roles WHERE rolname = 'billing_app' AND (rolbypassrls OR rolsuper)), 0,
                   'billing_app no tiene BYPASSRLS ni SUPERUSER');
SELECT t.expect_eq((SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = 'billing_app'), 0,
                   'billing_app no es dueño de ninguna tabla');
SELECT t.expect_eq((
    SELECT count(*) FROM information_schema.columns c
    JOIN pg_class k ON k.relname = c.table_name AND k.relnamespace = 'public'::regnamespace
    WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND NOT k.relrowsecurity), 0,
    'toda tabla con tenant_id tiene RLS activada');

\echo '== TODAS LAS PRUEBAS SQL PASARON =='
