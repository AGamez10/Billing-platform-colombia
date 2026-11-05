-- Roles de base de datos (se ejecuta una vez por clúster, como superusuario).
-- Separación de privilegios:
--   billing_owner: dueño del esquema; solo lo usa Flyway para migrar. BYPASSRLS para
--                  las funciones SECURITY DEFINER (auth, registro, cola); nunca la usa la app.
--   billing_app:   lo usa la aplicación en ejecución. No es dueño de tablas, no tiene
--                  BYPASSRLS ni SUPERUSER: Row-Level Security siempre le aplica.
-- Las contraseñas llegan por variables de entorno (psql -v); nunca se versionan.

\set ON_ERROR_STOP on

SELECT format('CREATE ROLE billing_owner LOGIN PASSWORD %L NOSUPERUSER NOCREATEROLE BYPASSRLS', :'owner_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billing_owner') \gexec

SELECT format('CREATE ROLE billing_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billing_app') \gexec

SELECT 'CREATE DATABASE billing OWNER billing_owner'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'billing') \gexec

\connect billing
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO billing_owner;
GRANT USAGE ON SCHEMA public TO billing_app;
-- btree_gist es una extensión "trusted", pero se crea aquí para no depender de ello.
CREATE EXTENSION IF NOT EXISTS btree_gist;
