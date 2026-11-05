-- Roles para pruebas de integración (equivalente JDBC de infrastructure/postgres/init/01-roles.sql).
-- Contraseñas solo de prueba, en un contenedor desechable.
CREATE ROLE billing_owner LOGIN PASSWORD 'owner-test' NOSUPERUSER NOCREATEROLE BYPASSRLS;
CREATE ROLE billing_app LOGIN PASSWORD 'app-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE EXTENSION IF NOT EXISTS btree_gist;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO billing_owner;
GRANT USAGE ON SCHEMA public TO billing_app;
