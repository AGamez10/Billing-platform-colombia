#!/usr/bin/env bash
# Prueba de restauración: descifra una copia, la restaura en un PostgreSQL desechable (sin tocar
# producción) y verifica que esté completa y que el aislamiento por organización siga activo.
# Hazla al menos una vez al mes, en tu computador o en el servidor, con la llave PRIVADA.
#
# Uso: scripts/restore-check.sh copia.dump.age ruta/llave-privada.txt
set -euo pipefail
backup="${1:?Uso: restore-check.sh copia.dump.age llave-privada}"
identity="${2:?Falta la llave privada age}"
if [ ! -f "$backup" ] || [ ! -f "$identity" ]; then echo "No existe la copia o la llave" >&2; exit 1; fi
here="$(cd "$(dirname "$0")/../.." && pwd)"

if [ -f "$backup.sha256" ]; then
  if ! (cd "$(dirname "$backup")" && sha256sum --check --quiet "$(basename "$backup").sha256"); then
    echo "La suma SHA-256 no coincide" >&2; exit 1
  fi
fi

name="billing-restore-check-$$"
pw="$(openssl rand -hex 16)"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$name" \
  -e POSTGRES_PASSWORD="$pw" -e BILLING_DB_NAME=billing \
  -e BILLING_DB_OWNER_PASSWORD="$(openssl rand -hex 16)" -e BILLING_DB_APP_PASSWORD="$(openssl rand -hex 16)" \
  -v "$here/infrastructure/postgres/init:/billing-init:ro" \
  -v "$here/infrastructure/postgres/docker-entrypoint-initdb.d:/docker-entrypoint-initdb.d:ro" \
  postgres:16 >/dev/null
for _ in $(seq 1 60); do
  docker exec "$name" pg_isready -h 127.0.0.1 -U postgres -d billing >/dev/null 2>&1 && break
  sleep 2
done

echo "Restaurando $(basename "$backup")…"
age --decrypt --identity "$identity" "$backup" \
  | docker exec -i "$name" pg_restore -U postgres -d billing --exit-on-error --single-transaction

q() { docker exec "$name" psql -U postgres -d billing -At -c "$1"; }
version=$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")
orgs=$(q "SELECT count(*) FROM organizations")
invoices=$(q "SELECT count(*) FROM invoices")
users=$(q "SELECT count(*) FROM users")
# billing_app sin organización en contexto no ve nada: RLS sigue activo tras restaurar.
leaked=$(docker exec "$name" psql -U postgres -d billing -qAt -c "SET ROLE billing_app" -c "SELECT count(*) FROM invoices")
if [ -z "$version" ] || [ "$version" -lt 1 ]; then echo "FALLO: sin historial de migraciones" >&2; exit 1; fi
if [ "$leaked" != "0" ]; then echo "FALLO: billing_app ve $leaked facturas sin organización" >&2; exit 1; fi
echo "RESTAURACIÓN OK: migración V$version · $orgs organizaciones · $users usuarios · $invoices documentos · RLS activo"
