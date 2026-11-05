# Despliegue

## Entorno local con Docker (verificado en CI)

`docker compose up --build` en la raíz levanta PostgreSQL 16 y el backend:

- `backend/Dockerfile`: compilación multi-etapa con Maven; la imagen final solo tiene el JRE 21 y corre con un usuario sin privilegios (`app`).
- PostgreSQL se inicializa la primera vez con `infrastructure/postgres/docker-entrypoint-initdb.d/10-billing-roles.sh`, que reutiliza `01-roles.sql` (roles `billing_owner` y `billing_app`, base `billing`, `btree_gist`). Flyway migra al arrancar el backend.
- Puertos publicados solo en `127.0.0.1`. Contraseñas por defecto de desarrollo, sobrescribibles con `.env` (ver `.env.example`).
- El job `docker` del CI construye la imagen, levanta el stack y verifica: salud, página de estado, planes, tarifas, OpenAPI, que `/actuator/env` esté cerrado y que el contenedor no corra como root.

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

## Objetivo: VPS único (planeado, sin verificar)

```
Internet ─▶ Caddy/Nginx (TLS, HSTS) ─┬─▶ frontend estático
                                     └─▶ backend Spring Boot (1 proceso, worker incluido) ─▶ PostgreSQL
```

- Docker Compose con tres servicios: proxy, backend, PostgreSQL (volumen dedicado). El frontend se sirve como estáticos desde el proxy.
- Tamaño inicial razonable: 2 vCPU / 4 GB RAM. Ajustar con métricas reales.
- Secretos: archivo `.env` fuera del repositorio con permisos 600, o gestor de secretos del proveedor del VPS.
- Backups: `pg_dump` diario cifrado a almacenamiento externo + retención 30 días; prueba de restauración mensual. Evaluar WAL archiving cuando haya clientes en producción.
- Observabilidad: Actuator (health/metrics) no expuesto públicamente; logs JSON sin secretos ni datos tributarios completos.
- Ambientes separados (desarrollo, habilitación, producción) con bases y credenciales de proveedor distintas.

Esta sección se convertirá en una guía paso a paso cuando exista el build completo y se haya desplegado al menos una vez.
