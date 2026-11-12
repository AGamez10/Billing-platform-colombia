#!/usr/bin/env bash
# Completa en deploy/.env los secretos que estén vacíos, con valores aleatorios fuertes.
# Nunca reemplaza uno existente (cambiar la clave de la base o de cifrado rompería el servicio).
# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

if [ ! -f "$ENV_FILE" ]; then
  install -m 600 "$DEPLOY_DIR/.env.example" "$ENV_FILE"
  log "Creado $ENV_FILE desde .env.example"
fi
chmod 600 "$ENV_FILE"

fill() {
  local key="$1" value="$2"
  if grep -qE "^${key}=.+" "$ENV_FILE"; then
    log "$key ya tiene valor: se conserva"
  elif grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    log "$key generado"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
    log "$key generado"
  fi
}

# Contraseñas solo con caracteres seguros para URL y psql (sin comillas ni barras).
pw() { local s; s=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9'); printf '%s' "${s:0:40}"; }

fill POSTGRES_SUPERUSER_PASSWORD "$(pw)"
fill BILLING_DB_OWNER_PASSWORD "$(pw)"
fill BILLING_DB_APP_PASSWORD "$(pw)"
fill BILLING_JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
fill BILLING_SECRETS_KEY "$(openssl rand -base64 32 | tr -d '\n')"

log "Listo. Completa a mano: BILLING_VERSION, BILLING_DOMAIN, ACME_EMAIL, BILLING_BRAND_NAME, correo y copias."
log "Guarda BILLING_SECRETS_KEY también en tu gestor de contraseñas."
