#!/usr/bin/env bash
# Lo ejecuta la imagen oficial de PostgreSQL solo la PRIMERA vez que crea el volumen de datos.
# Crea los roles billing_owner / billing_app y la base, reutilizando el script versionado.
set -euo pipefail

: "${BILLING_DB_OWNER_PASSWORD:?Falta BILLING_DB_OWNER_PASSWORD}"
: "${BILLING_DB_APP_PASSWORD:?Falta BILLING_DB_APP_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  -v owner_password="$BILLING_DB_OWNER_PASSWORD" \
  -v app_password="$BILLING_DB_APP_PASSWORD" \
  -v db_name="${BILLING_DB_NAME:-billing}" \
  -f /billing-init/01-roles.sql
