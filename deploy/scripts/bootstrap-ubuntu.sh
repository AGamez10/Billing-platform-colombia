#!/usr/bin/env bash
# Preparación inicial de un VPS Ubuntu 24.04 limpio (se ejecuta UNA vez, como root).
# Instala Docker (repositorio oficial), age, rclone y actualizaciones automáticas; crea el
# usuario "billing"; cierra todo menos SSH, HTTP y HTTPS; y endurece SSH a solo llaves.
#
# Antes de correrlo: entra por SSH con una LLAVE (no contraseña) y deja una segunda sesión
# abierta por si algo sale mal con SSH.
#
# Uso: sudo bash bootstrap-ubuntu.sh
set -euo pipefail
[ "$(id -u)" = "0" ] || { echo "Ejecuta como root (sudo)" >&2; exit 1; }
# shellcheck source=/dev/null
. /etc/os-release
[ "$ID" = "ubuntu" ] || { echo "Probado solo en Ubuntu (24.04)" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y upgrade
apt-get install -y ca-certificates curl git ufw unattended-upgrades fail2ban age rclone

# Docker Engine + plugin compose desde el repositorio oficial de Docker.
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

# Usuario sin privilegios que opera la plataforma (pertenecer a "docker" equivale a root en
# la práctica: no se le da contraseña y solo se entra con la llave del dueño).
id billing >/dev/null 2>&1 || useradd --create-home --shell /bin/bash billing
usermod -aG docker billing
install -d -o billing -g billing -m 750 /opt/billing

# Cortafuegos: solo SSH, HTTP y HTTPS (HTTP solo para el reto de Let's Encrypt y redirigir).
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable
# Nota: los puertos publicados por Docker no pasan por ufw. Por eso compose.prod.yml solo
# publica 80/443 (Caddy) y PostgreSQL no tiene puerto publicado.

# SSH solo con llave.
cat > /etc/ssh/sshd_config.d/10-billing-hardening.conf <<'SSHD'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
SSHD
systemctl reload ssh || systemctl reload sshd

# Parches de seguridad automáticos.
dpkg-reconfigure -f noninteractive unattended-upgrades
systemctl enable --now fail2ban

echo "Listo. Siguiente: clonar el repositorio en /opt/billing como el usuario billing (ver docs/DEPLOYMENT.md)."
