#!/usr/bin/env bash
# Despliega (o actualiza a) una versión publicada: copia de seguridad previa, descarga de
# imágenes, arranque y verificación por HTTPS. Las migraciones de base las aplica el backend al
# arrancar (Flyway); por eso la copia previa es obligatoria cuando ya hay datos.
#
# Uso: scripts/deploy.sh v0.1.0
# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

version="${1:?Uso: deploy.sh vX.Y.Z}"
[[ "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$|^sha-[0-9a-f]{7,40}$ ]] || die "Versión inválida: $version"
require_private_env
domain="$(env_value BILLING_DOMAIN)"
[ -n "$domain" ] || die "Falta BILLING_DOMAIN en $ENV_FILE"

sed -i "s|^BILLING_VERSION=.*|BILLING_VERSION=$version|" "$ENV_FILE"
grep -q "^BILLING_VERSION=" "$ENV_FILE" || printf 'BILLING_VERSION=%s\n' "$version" >> "$ENV_FILE"

if "${COMPOSE[@]}" ps --status running --services 2>/dev/null | grep -qx postgres; then
  "$DEPLOY_DIR/scripts/backup.sh" "pre-$version" || die "La copia previa falló: no se actualiza sin copia"
fi

if [ "${BILLING_SKIP_PULL:-0}" != "1" ]; then
  log "Descargando imágenes $version"
  "${COMPOSE[@]}" pull
fi
log "Arrancando"
"${COMPOSE[@]}" up -d --remove-orphans

url="https://$domain"
curl_opts=(-fsS --max-time 10)
[ "$domain" = "localhost" ] && curl_opts+=(-k)
for i in $(seq 1 60); do
  if curl "${curl_opts[@]}" "$url/api/v1/public/legal" >/dev/null 2>&1 && curl "${curl_opts[@]}" "$url/" | grep -q '<div id="root">'; then
    log "Versión $version en línea en $url (${i}x5s)"
    exit 0
  fi
  sleep 5
done
"${COMPOSE[@]}" logs --tail 80 backend caddy >&2 || true
die "La versión $version no respondió en 5 minutos. Para volver atrás: scripts/deploy.sh <versión anterior> (la base conserva las migraciones nuevas; si hace falta, restaura la copia pre-$version)"
