# Despliegue

## Hoy: base de datos (verificado)

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
