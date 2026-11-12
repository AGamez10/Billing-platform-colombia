# shellcheck shell=bash
# Funciones compartidas por los scripts de despliegue. Se incluye con: . "$(dirname "$0")/common.sh"
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
# shellcheck disable=SC2034 # lo usan los scripts que incluyen este archivo
COMPOSE=(docker compose --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/compose.prod.yml" --env-file "$ENV_FILE")

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Lee una variable del .env sin ejecutarlo (no se hace "source" de un archivo con secretos).
env_value() {
  local key="$1"
  [ -f "$ENV_FILE" ] || die "No existe $ENV_FILE"
  grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'
}

require_private_env() {
  [ -f "$ENV_FILE" ] || die "No existe $ENV_FILE (copia .env.example y ejecuta init-secrets.sh)"
  local mode
  mode=$(stat -c '%a' "$ENV_FILE")
  [ "$mode" = "600" ] || [ "$mode" = "400" ] || die "$ENV_FILE debe tener permisos 600 (tiene $mode)"
}
