# API REST v1 — diseño

> **No implementada todavía.** Este es el contrato objetivo; se publicará con OpenAPI generado desde el código cuando exista la capa web. Ninguna ruta de esta página responde hoy.

## Convenciones

- Base: `/api/v1`. JSON UTF-8. Montos como cadenas decimales (`"119000.00"`), nunca números de punto flotante.
- Autenticación: `Authorization: Bearer <access JWT>` (usuarios) o `Authorization: Bearer bpk_…` (API keys de integraciones).
- El tenant **no** se envía: sale del token. Un id de otra organización responde `404`, igual que uno inexistente.
- `POST` acepta `Idempotency-Key` (8–128 caracteres): misma clave y mismo cuerpo → misma respuesta; cuerpo distinto → `422`.
- Errores: `application/problem+json` (RFC 9457) con `code` estable (`INVALID_NIT`, `NUMBERING_RANGE_EXHAUSTED`, …), sin trazas internas.
- Listados paginados por cursor; `limit` máximo 100.

## Recursos

| Método y ruta | Rol mínimo | Descripción |
|---|---|---|
| `POST /auth/register` | público (rate limit) | Crea organización + usuario dueño |
| `POST /auth/login` · `POST /auth/refresh` · `POST /auth/logout` | público / cookie | Sesión |
| `GET/POST /users`, `PATCH /users/{id}` | ADMIN | Membresías y roles |
| `GET/POST /api-keys`, `DELETE /api-keys/{id}` | ADMIN | Claves de integración (la clave completa se muestra una sola vez) |
| `GET/POST /customers`, `GET/PATCH /customers/{id}` | BILLER | Adquirientes (NIT con DV validado) |
| `GET/POST /products`, `GET/PATCH /products/{id}` | BILLER | Productos y servicios con códigos de tarifa |
| `GET /tax-rates` | VIEWER | Tarifas vigentes con fundamento legal |
| `GET/POST /numbering-ranges` | ADMIN | Resoluciones de numeración |
| `POST /invoices` | BILLER | Crea borrador; el servidor calcula totales |
| `PUT /invoices/{id}` · `DELETE /invoices/{id}` | BILLER | Solo borradores |
| `POST /invoices/{id}/finalize` | BILLER | Numera y encola transmisión |
| `GET /invoices`, `GET /invoices/{id}` | VIEWER | Incluye `status`, `environment`, `legallyValid`, historial |
| `GET/POST /provider-accounts`, `POST /provider-accounts/{id}/activate` | OWNER | Proveedor por organización |
| `POST /webhooks/{providerId}/{token}` | firma del proveedor | Notificaciones de proveedor |
| `GET /usage` | ADMIN | Consumo del periodo |

## Campo `legallyValid`

Toda respuesta de factura incluye `status`, `validationEnvironment` y `legallyValid`. La interfaz solo puede mostrar "Factura electrónica válida" cuando `legallyValid = true` (validada con evidencia en producción).
