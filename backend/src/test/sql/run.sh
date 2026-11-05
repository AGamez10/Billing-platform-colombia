#!/usr/bin/env bash
# Crea una base desechable, aplica roles + migraciones (como lo haría Flyway) y ejecuta
# las pruebas de aislamiento. Requiere un PostgreSQL local accesible como superusuario.
# Uso: PGSUPERUSER=postgres backend/src/test/sql/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
SU="${PGSUPERUSER:-postgres}"
PSQL=(psql -v ON_ERROR_STOP=1 -X -q)
DB=billing

as_su() { if [ "$(id -un)" = "$SU" ]; then "$@"; else sudo -u "$SU" "$@"; fi; }

as_su "${PSQL[@]}" -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "DROP ROLE IF EXISTS billing_app" -c "DROP ROLE IF EXISTS billing_owner"
as_su "${PSQL[@]}" -d postgres \
  -v owner_password="test-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')" \
  -v app_password="test-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')" \
  -f "$ROOT/infrastructure/postgres/init/01-roles.sql"

for f in $(ls "$ROOT"/backend/src/main/resources/db/migration/V*__*.sql | sort -V); do
  echo "migrate: $(basename "$f")"
  sed 's/\${app_role}/billing_app/g' "$f" \
    | as_su "${PSQL[@]}" -d "$DB" -c "SET ROLE billing_owner" -f - >/dev/null
done

# Solo se muestran las líneas de resultado (PASS/FAIL/encabezados) y los errores.
as_su "${PSQL[@]}" -t -A -d "$DB" -f "$HERE/tenant_isolation_test.sql" 2>&1 \
  | sed 's/^psql:[^:]*:[0-9]*: NOTICE:  //' \
  | grep -E '^(PASS|FAIL|==|psql:|ERROR|CONTEXT)' \
  | tee "${TMPDIR:-/tmp}/tenant_isolation_test.log"
exit "${PIPESTATUS[0]}"
