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

## Objetivo: VPS en Hostinger (planeado, sin verificar; ADR-0016)

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
- Ambientes separados (desarrollo, habilitación, producción) con bases y credenciales de proveedor distintas.

Esta sección se convertirá en una guía paso a paso cuando exista el build completo y se haya desplegado al menos una vez.
