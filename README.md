# Billing Platform Colombia

SaaS multi-tenant de facturación electrónica para Colombia: organizaciones, clientes, productos, facturas y transmisión a través de proveedores tecnológicos autorizados, intercambiables por configuración.

## Estado (2026-11-05)

| Área | Estado |
|---|---|
| Núcleo de dominio (impuestos, facturas, numeración, contrato de proveedores, adaptador simulado, envío idempotente, webhooks, borrador/finalización, cuotas, identidad) | Implementado, 82 pruebas pasan |
| Esquema PostgreSQL con Row-Level Security (V1–V5) | Implementado, 49 pruebas SQL pasan |
| Arranque Spring Boot + CI en GitHub Actions | Implementado: 84 pruebas (JUnit/Surefire) y 49 SQL en verde en CI |
| REST, seguridad web, adaptadores de persistencia, worker, frontend | Pendiente |
| Adaptador de proveedor real | Pendiente de contrato |

Detalle en [docs/ROADMAP.md](docs/ROADMAP.md).

## Levantar todo en tu computador

Requisito: Docker Desktop (Windows/Mac) o Docker Engine con Compose (Linux).

```bash
git clone https://github.com/AGamez10/Billing-platform-colombia.git
cd Billing-platform-colombia
docker compose up --build
```

Abre **http://localhost:8080**: página de estado con planes y tarifas vigentes, y enlace a la documentación interactiva de la API (`/swagger-ui.html`). La primera construcción tarda varios minutos (descarga dependencias); las siguientes son rápidas.

- Detener: `Ctrl+C` o `docker compose down`. Borrar también la base: `docker compose down -v`.
- Ver los cambios más recientes: `git pull` y de nuevo `docker compose up --build`.
- Contraseñas: por defecto usa valores de desarrollo; para cambiarlas copia `.env.example` como `.env`. Los puertos solo se abren en tu propio equipo (127.0.0.1).

## Estructura

```
backend/      Java 21, Spring Boot 4.1, migraciones Flyway, pruebas
docs/         ARCHITECTURE, MULTITENANCY, SECURITY, PROVIDERS, DECISIONS (ADR), ROADMAP, API, DEPLOYMENT
infrastructure/postgres/init/   roles de base de datos
```

## Pruebas

```bash
# Pruebas Java (con acceso a Maven Central)
cd backend && mvn verify

# Aislamiento multi-tenant e invariantes contra PostgreSQL real (requiere PostgreSQL 16 local)
backend/src/test/sql/run.sh
```

## Principios no negociables

- El tenant sale de la identidad autenticada, nunca del cliente; RLS como segunda barrera.
- Dinero en `BigDecimal` con redondeo explícito.
- Ningún documento se presenta como validado sin evidencia del proveedor, y solo es válido tributariamente si se validó en producción.
- El dominio no conoce a ningún proveedor; las capacidades se consultan, no se suponen.
