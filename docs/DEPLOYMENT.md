# Despliegue

## Entorno local con Docker (verificado en CI)

`docker compose up --build` en la raíz levanta PostgreSQL 16, el backend y la interfaz web:

- **http://localhost:3000**: la aplicación (nginx sin root sirve la interfaz y pasa `/api` al backend, mismo origen).
- **http://localhost:8080**: backend directo (página de estado y `/swagger-ui.html`).
- `frontend/Dockerfile`: construye con pnpm (`--frozen-lockfile`) y sirve con `nginxinc/nginx-unprivileged`. El nombre comercial se pasa con `BILLING_BRAND_NAME` en `.env`.
- El lockfile `frontend/pnpm-lock.yaml` se genera en CI (workflow "Lockfile de la interfaz"), porque el entorno de desarrollo no alcanza el registro de paquetes.

- `backend/Dockerfile`: compilación multi-etapa con Maven; la imagen final solo tiene el JRE 21 y corre con un usuario sin privilegios (`app`).
- PostgreSQL se inicializa la primera vez con `infrastructure/postgres/docker-entrypoint-initdb.d/10-billing-roles.sh`, que reutiliza `01-roles.sql` (roles `billing_owner` y `billing_app`, base `billing`, `btree_gist`). Flyway migra al arrancar el backend.
- Puertos publicados solo en `127.0.0.1`. Contraseñas por defecto de desarrollo, sobrescribibles con `.env` (ver `.env.example`).
- El job `docker` del CI construye las imágenes, levanta el stack y verifica: salud, página de estado, planes, tarifas, OpenAPI, que `/actuator/env` esté cerrado, que ningún contenedor corra como root, las cabeceras de seguridad de la interfaz y el proxy `/api`. Además corre **Playwright con un navegador real** (escritorio y celular): registro → modo simulación → resolución → cliente → factura → emisión → aceptada en simulación.

## Base de datos sin Docker (verificado)

Requiere PostgreSQL 16+ con la extensión `btree_gist` (contrib).

```bash
# 1) Roles y base (como superusuario). Contraseñas desde variables de entorno, nunca en el repo.
psql -d postgres -v owner_password="$BILLING_OWNER_PASSWORD" -v app_password="$BILLING_APP_PASSWORD" \
     -f infrastructure/postgres/init/01-roles.sql

# 2) Migraciones: Flyway con usuario billing_owner y placeholder app_role=billing_app.
#    (Mientras no exista el build Spring, backend/src/test/sql/run.sh aplica las
#    migraciones en una base desechable y ejecuta las pruebas de aislamiento.)
```

La aplicación debe conectarse **siempre** como `billing_app`. `billing_owner` solo lo usa Flyway.

## Producción en un VPS (Hostinger u otro) — guía paso a paso

Kit en `deploy/` (ADR-0026). Lo que está **verificado en CI** en cada cambio (job "Kit de producción"): las imágenes, el arranque con `compose.prod.yml`, HTTPS con Caddy (en `localhost`), redirección HTTP→HTTPS, HSTS y CSP, `/actuator` cerrado, PostgreSQL sin puerto publicado, registro por el dominio, copia cifrada, restauración en una base desechable con RLS activo y la actualización con copia previa. Lo que **no** se ha verificado todavía: un VPS real de Hostinger, Let's Encrypt con un dominio real y rclone contra un almacenamiento externo real.

```
Internet ─▶ Caddy :443 (Let's Encrypt, HSTS) ─▶ web (nginx: interfaz + /api) ─▶ backend (Spring Boot + worker) ─▶ PostgreSQL
                                                                              (red interna de Docker; solo Caddy publica puertos)
```

### 0. Lo que necesitas antes
- VPS con Ubuntu 24.04, **2 vCPU / 4 GB** (con 2 GB funciona si es dedicado; medir). Dedicado a esta plataforma (ADR-0016).
- Un dominio (o subdominio, p. ej. `app.tumarca.co`) con registros **A** (y AAAA si hay IPv6) apuntando a la IP del VPS.
- Una cuenta de correo transaccional (Amazon SES u otro SMTP con STARTTLS) con el dominio verificado y SPF/DKIM/DMARC.
- Almacenamiento externo para copias (Backblaze B2, Cloudflare R2, S3…) con una llave de acceso **solo a ese bucket**.
- Tu llave SSH. Nunca contraseñas por SSH.

### 1. Preparar el servidor (una vez)
```bash
scp deploy/scripts/bootstrap-ubuntu.sh root@IP:/root/
ssh root@IP 'bash /root/bootstrap-ubuntu.sh'
```
Instala Docker oficial, `age`, `rclone`, fail2ban y parches automáticos; crea el usuario `billing`; deja abiertos solo 22, 80 y 443; SSH solo con llave. Copia tu llave pública a `/home/billing/.ssh/authorized_keys`.

### 2. Traer el kit
Como `billing`: crea una **deploy key de solo lectura** en GitHub (Settings → Deploy keys) y clona:
```bash
git clone git@github.com:AGamez10/Billing-platform-colombia.git /opt/billing
cd /opt/billing && git checkout v0.1.0      # la misma etiqueta que vas a desplegar
```
Las imágenes son privadas en GHCR: `docker login ghcr.io` con un token personal *classic* con solo `read:packages`.

### 3. Configurar
```bash
cd /opt/billing/deploy
scripts/init-secrets.sh        # crea .env (600) y genera contraseñas y llaves aleatorias
nano .env                      # dominio, correo ACME, marca, SMTP, Mercado Pago, copias
```
- **Copias:** genera el par de llaves age **en tu computador**, no en el servidor: `age-keygen -o billing-backups.key`. Pon en `.env` solo la pública (`BACKUP_AGE_RECIPIENT=age1…`). Guarda la privada en tu gestor de contraseñas y una copia impresa: sin ella las copias no sirven.
- **Destino externo:** `rclone config` (como `billing`) y `BACKUP_RCLONE_REMOTE=nombre:bucket/billing`.
- **BILLING_SECRETS_KEY** (cifra las llaves Bold guardadas): guárdala también en tu gestor de contraseñas.
- **Mercado Pago:** access token y clave secreta de webhooks en `.env` (nunca en el chat ni en el repositorio). En el panel de la aplicación de Mercado Pago, webhook `https://<dominio>/api/v1/webhooks/mercadopago`, evento *Pagos*. Con credenciales de prueba, `BILLING_MERCADOPAGO_REQUIRE_LIVE_MODE=false`; en real, `true`.

### 4. Publicar una versión y desplegar
En tu equipo: `git tag v0.1.0 && git push origin v0.1.0`. El flujo **Release** publica `ghcr.io/agamez10/billing-platform-{backend,web}:v0.1.0` (la marca de la interfaz sale de la variable del repositorio `BILLING_BRAND_NAME`). En el servidor:
```bash
/opt/billing/deploy/scripts/deploy.sh v0.1.0
```
Hace copia previa (si ya hay base), descarga imágenes, arranca, espera a que `https://<dominio>` responda y, si no, muestra los registros. Las migraciones las aplica el backend al arrancar.

### 5. Copias automáticas y prueba de restauración
```bash
sudo cp /opt/billing/deploy/systemd/billing-backup.* /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now billing-backup.timer
systemctl list-timers billing-backup.timer
```
Copia diaria a las 3:15 a. m. (Colombia): local 7 días, externa 30 días. **Una vez al mes**, en tu computador (con Docker) y la llave privada:
```bash
rclone copy remoto:bucket/billing/billing-<fecha>-daily.dump.age .
deploy/scripts/restore-check.sh billing-<fecha>-daily.dump.age ~/billing-backups.key
```
Debe terminar en `RESTAURACIÓN OK`. Para restaurar de verdad (desastre): servidor nuevo con pasos 1–3, `docker compose ... up -d postgres`, y `age -d -i llave copia | docker compose ... exec -T postgres pg_restore -U postgres -d billing --clean --if-exists --single-transaction`; luego `deploy.sh`.

### 6. Operación
- Actualizar: nueva etiqueta → `deploy.sh vX.Y.Z`. Volver atrás: `deploy.sh <anterior>` (si la versión nueva migró la base de forma incompatible, restaurar la copia `pre-vX.Y.Z`).
- Registros: `docker compose --project-directory /opt/billing/deploy -f /opt/billing/deploy/compose.prod.yml logs -f backend` (rotan a 5×10 MB por servicio).
- Ambientes: este kit es para **un** ambiente. Habilitación y producción del proveedor se distinguen dentro de la app por cuenta; un ambiente de pruebas de la plataforma iría en otro servidor o directorio con su propio `.env` y dominio.

## Notas de diseño del despliegue


```
Internet ─▶ Caddy/Nginx (TLS, HSTS) ─┬─▶ frontend estático
                                     └─▶ backend Spring Boot (1 proceso, worker incluido) ─▶ PostgreSQL
```

- Docker Compose con tres servicios: proxy, backend, PostgreSQL (volumen dedicado). El frontend se sirve como estáticos desde el proxy.
- Tamaño inicial razonable: 2 vCPU / 4 GB RAM. Ajustar con métricas reales.
- Preferible un VPS dedicado. Si comparte VPS con otro producto: PostgreSQL, red Docker, secretos y backups propios, y límites de CPU/RAM por contenedor (`deploy.resources.limits`). Ningún puerto de PostgreSQL publicado.
- Secretos: archivo `.env` fuera del repositorio con permisos 600, o gestor de secretos del proveedor del VPS.
- Backups: `pg_dump` diario cifrado a almacenamiento externo + retención 30 días; prueba de restauración mensual. Evaluar WAL archiving cuando haya clientes en producción.
- Observabilidad: Actuator (health/metrics) no expuesto públicamente; logs JSON sin secretos ni datos tributarios completos.
- Correo transaccional (recuperar contraseña, invitaciones): SMTP con STARTTLS. Opción recomendada por costo: Amazon SES (del orden de USD 0,10 por cada mil correos según su lista pública; confirmar el precio vigente antes de contratar), con el dominio verificado y SPF, DKIM y DMARC publicados para no caer en spam. Variables: `BILLING_MAIL_MODE=smtp`, `BILLING_MAIL_FROM`, `BILLING_SMTP_HOST`, `BILLING_SMTP_PORT=587`, `BILLING_SMTP_USERNAME`, `BILLING_SMTP_PASSWORD`, `BILLING_WEB_BASE_URL` (la URL pública con HTTPS). En local, Docker Compose levanta Mailpit (http://localhost:8025) y nada sale a internet.
- Pago de planes con Mercado Pago (ADR-0024): `BILLING_MERCADOPAGO_ACCESS_TOKEN`, `BILLING_MERCADOPAGO_WEBHOOK_SECRET` y `BILLING_MERCADOPAGO_REQUIRE_LIVE_MODE=true`. En el panel de la aplicación de Mercado Pago, configurar el webhook `https://<dominio>/api/v1/webhooks/mercadopago` con el evento de pagos. Las credenciales nunca van al repositorio ni al chat.
- Ambientes separados (desarrollo, habilitación, producción) con bases y credenciales de proveedor distintas.


