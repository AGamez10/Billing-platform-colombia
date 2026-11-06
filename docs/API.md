# API REST v1

La documentación interactiva se genera desde el código: `http://localhost:8080/swagger-ui.html` (botón **Authorize** con el `accessToken`).

## Convenciones

- Base: `/api/v1`. JSON UTF-8. Los montos y precios se escriben como cadenas decimales (`"40000.50"`) y se responden como números decimales exactos. Nunca se usa punto flotante.
- Autenticación: `Authorization: Bearer <access JWT>`. Las API keys de integración (`bpk_…`) ya existen en el núcleo, pero todavía no se aceptan en las rutas web.
- El tenant **no** se envía: sale del token. Un id de otra organización responde `404`, igual que uno inexistente.
- Errores: `application/problem+json` (RFC 9457) con `code` estable (`INVALID_NIT`, `NUMBERING_RANGE_NOT_FOUND`, `DOCUMENT_QUOTA_REACHED`, …) y sin trazas internas. Validación de forma → `400`; regla de negocio → `422`; conflicto de estado → `409`; plan o cuota → `402`.
- Listados paginados con `limit` (máx. 100) y `offset`. La respuesta trae `items` y `hasMore`.

## Implementado (con pruebas HTTP contra PostgreSQL real)

| Método y ruta | Permiso | Descripción |
|---|---|---|
| `POST /auth/register` | público (rate limit) | Crea la organización, el usuario dueño y la prueba gratis (20 documentos) |
| `POST /auth/login` · `/auth/refresh` · `/auth/logout` · `GET /auth/me` | público / cookie / token | Sesión (refresh rotatorio en cookie segura) |
| `GET/POST /customers`, `GET/PUT/DELETE /customers/{id}` | CUSTOMER_* | Adquirientes. El NIT se valida con su DV. Bloqueo optimista con `version`; DELETE desactiva |
| `GET/POST /products`, `GET/PUT/DELETE /products/{id}` | PRODUCT_* | Productos y servicios con códigos de tarifa vigentes |
| `GET /public/plans`, `GET /public/tax-rates` | público | Catálogo comercial y tarifas con fundamento legal |
| `GET/POST /provider-accounts` | POST: PROVIDER_MANAGE (solo dueño) | Proveedor y ambiente activos para nuevas facturas. Hoy solo está disponible `mock` en `SIMULATION` |
| `GET/POST /numbering-ranges` | POST: NUMBERING_MANAGE | Resolución de numeración tal como figura en la resolución DIAN, por ambiente |
| `POST /invoices` | INVOICE_WRITE | Crea un borrador. Totales e impuestos los calcula el servidor; no consume número |
| `PUT /invoices/{id}` · `DELETE /invoices/{id}` | INVOICE_WRITE | Solo borradores (`409` si ya se emitió) |
| `POST /invoices/{id}/finalize` | INVOICE_FINALIZE | Verifica la cuota, asigna el número consecutivo y encola el envío. Es idempotente |
| `GET /invoices/{id}` | INVOICE_READ | Devuelve estado, número, ambiente, totales, subtotales por tarifa, evidencia del proveedor e historial |
| `GET /invoices?status=` | INVOICE_READ | Listado, más recientes primero |
| `GET /subscription/quota` | USAGE_READ | Documentos usados en el ciclo. Solo cuentan los emitidos en PRODUCTION |
| `POST /subscription/overage-consent` | BILLING_MANAGE (dueño/admin) | Aceptación expresa de documentos adicionales a $150 en el ciclo actual (ADR-0014) |

### Flujo mínimo para emitir (simulación)

1. `POST /provider-accounts` con `{"providerId":"mock","environment":"SIMULATION"}`.
2. `POST /numbering-ranges` con `environment` `SIMULATION`, número de resolución, prefijo, rango y vigencia.
3. `POST /customers`.
4. `POST /invoices` con `customerId` y `lines`. Cada línea lleva `description`, `unitCode`, `quantity`, `unitPrice` y, opcionalmente, `discount {kind: PERCENT|AMOUNT, value}` y `taxRateCodes`.
5. `POST /invoices/{id}/finalize`. El worker la envía en segundos.
6. `GET /invoices/{id}` hasta que `status` sea `ACCEPTED`, `REJECTED` o `FAILED`.

## Campo `legallyValid`

Toda factura incluye `status`, `environment`, `validationEnvironment`, `simulated` y `legallyValid`. La interfaz solo puede mostrar "Factura electrónica válida" cuando `legallyValid = true`, es decir, cuando está validada con evidencia en **producción**. Lo emitido con el proveedor simulado lleva `simulated = true`, una clave `SIMULATED-…` y nunca tiene efecto tributario.

## Pendiente

- Cabecera `Idempotency-Key` en los POST (la tabla ya existe).
- API keys aceptadas en las rutas (integración de AdoptaFácil).
- Usuarios e invitaciones (`/users`), gestión de API keys (`/api-keys`).
- Webhooks de proveedor (`/webhooks/{providerId}/{token}`); el núcleo y la verificación de firma ya existen.
- Notas crédito, POS electrónico y documento soporte.
- Modo de sesión móvil (ADR-0015).
