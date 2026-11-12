#!/usr/bin/env bash
# Copia de seguridad cifrada de la base: pg_dump (formato custom) → age (llave pública) → disco
# local y, si está configurado, almacenamiento externo con rclone.
#
# El servidor solo tiene la llave PÚBLICA: quien entre al servidor no puede leer las copias.
# La llave privada vive fuera (gestor de contraseñas + papel) y solo se usa para restaurar.
#
# Uso: scripts/backup.sh [etiqueta]     (lo ejecuta el timer diario y deploy.sh antes de actualizar)
# shellcheck source=common.sh
. "$(dirname "$0")/common.sh"

label="${1:-daily}"
[[ "$label" =~ ^[a-z0-9.-]{1,40}$ ]] || die "Etiqueta inválida: $label"
recipient="${BACKUP_AGE_RECIPIENT:-$(env_value BACKUP_AGE_RECIPIENT)}"
[[ "$recipient" =~ ^age1[0-9a-z]{58}$ ]] || die "BACKUP_AGE_RECIPIENT debe ser una llave pública age (age1…)"
command -v age >/dev/null || die "Falta age (apt install age)"

dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
install -d -m 700 "$dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
file="$dir/billing-$stamp-$label.dump.age"
tmp="$file.partial"
trap 'rm -f "$tmp"' EXIT

log "Respaldando la base en $file"
# pipefail: si pg_dump falla, el script falla y no queda un archivo truncado con nombre final.
"${COMPOSE[@]}" exec -T postgres pg_dump -U postgres -d billing --format=custom --compress=6 \
  | age --encrypt --recipient "$recipient" > "$tmp"
size=$(stat -c '%s' "$tmp")
[ "$size" -gt 1024 ] || die "La copia pesa $size bytes: algo falló"
mv "$tmp" "$file"
chmod 600 "$file"
sha256sum "$file" | sed "s|$dir/||" > "$file.sha256"
log "Copia lista ($size bytes)"

remote="${BACKUP_RCLONE_REMOTE:-$(env_value BACKUP_RCLONE_REMOTE || true)}"
if [ -n "$remote" ]; then
  command -v rclone >/dev/null || die "BACKUP_RCLONE_REMOTE está configurado pero falta rclone"
  rclone copy --no-traverse "$file" "$remote/"
  rclone copy --no-traverse "$file.sha256" "$remote/"
  days="$(env_value BACKUP_REMOTE_DAYS || true)"
  rclone delete --min-age "${days:-30}d" "$remote/" || log "Aviso: no se pudieron borrar copias externas antiguas"
  log "Copia enviada a $remote"
else
  log "Aviso: sin BACKUP_RCLONE_REMOTE la copia queda solo en este servidor"
fi

days="$(env_value BACKUP_LOCAL_DAYS 2>/dev/null || true)"
find "$dir" -maxdepth 1 -name 'billing-*.dump.age*' -mtime +"${days:-7}" -delete
