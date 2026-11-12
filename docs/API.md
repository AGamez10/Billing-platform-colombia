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
| `GET/POST /customers`, `GET/PUT/DELETE /customers/{id}` | CUSTOMER_* | Adquirientes. El NIT se valida con su DV. `fiscal` opcional: `{personType: LEGAL\|NATURAL, vatRegime: RESPONSIBLE\|NOT_RESPONSIBLE, responsibilities: ["O-13"…] o ["R-99-PN"]}`; `cityCode` debe existir en la DIVIPOLA. Bloqueo optimista con `version`; DELETE desactiva |
| `GET/POST /products`, `GET/PUT/DELETE /products/{id}` | PRODUCT_* | Productos y servicios con códigos de tarifa vigentes |
| `GET /public/plans`, `GET /public/tax-rates` | público | Catálogo comercial y tarifas con fundamento legal |
| `GET /public/municipalities` | público (caché 1 día) | Municipios DIVIPOLA del DANE: `code` (5 dígitos), `name`, `departmentCode`, `departmentName` |
| `GET /organization` | cualquier miembro | Datos del emisor: identificación, razón social, contacto, `fiscal`, `address` (con nombre del municipio) y `readyToIssue` |
| `PUT /organization` | ORGANIZATION_MANAGE (dueño/admin) | `{legalName, tradeName?, email, phone?, addressLine, cityCode, fiscal}`. La identificación no se cambia. `422 UNKNOWN_MUNICIPALITY` si el código no está en la DIVIPOLA |
| `GET/POST /provider-accounts` | POST: PROVIDER_MANAGE (solo dueño) | Proveedor y ambiente activos para nuevas facturas. Hoy solo está disponible `mock` en `SIMULATION` |
| `GET/POST /numbering-ranges` | POST: NUMBERING_MANAGE | Resolución de numeración tal como figura en la resolución DIAN, por ambiente |
| `POST /invoices` | INVOICE_WRITE | Crea un borrador. Totales e impuestos los calcula el servidor; no consume número |
| `PUT /invoices/{id}` · `DELETE /invoices/{id}` | INVOICE_WRITE | Solo borradores (`409` si ya se emitió) |
| `POST /invoices/{id}/finalize` | INVOICE_FINALIZE | Verifica la cuota, asigna el número consecutivo y encola el envío. Es idempotente. Fuera de simulación exige perfil tributario del emisor y del cliente (`409 ISSUER_PROFILE_INCOMPLETE` / `CUSTOMER_PROFILE_INCOMPLETE`, ADR-0025) |
| `GET /invoices/{id}` | INVOICE_READ | Devuelve estado, número, ambiente, totales, subtotales por tarifa, evidencia del proveedor e historial |
| `GET /invoices?status=` | INVOICE_READ | Listado, más recientes primero |
| `GET /subscription/quota` | USAGE_READ | Documentos usados en el ciclo. Solo cuentan los emitidos en PRODUCTION |
| `POST /subscription/overage-consent` | BILLING_MANAGE (dueño/admin) | Aceptación expresa de documentos adicionales a $150 en el ciclo actual (ADR-0014) |
| `POST /auth/password-reset/request` | público (límite por IP) | Envía el enlace para cambiar la contraseña. Responde 202 exista o no la cuenta |
| `POST /auth/password-reset/confirm` | público | `{token, password}`: cambia la contraseña y cierra todas las sesiones. 410 si el enlace venció o ya se usó |
| `POST /auth/invitations/preview` | público | `{token}`: organización, correo, rol y si el correo ya tiene cuenta |
| `POST /auth/invitations/accept` | público | Cuenta nueva: `fullName`, `password`, versiones legales; cuenta existente: su `password`. Inicia sesión en la organización que invita |
| `GET /team` | USERS_MANAGE | Miembros activos, invitaciones abiertas y máximo de usuarios del plan |
| `POST /team/invitations` | USERS_MANAGE | `{email, role}` (ADMIN, BILLER, VIEWER; nunca más permisos que los propios). Reenviar reemplaza la anterior |
| `DELETE /team/invitations/{id}` | USERS_MANAGE | Anula una invitación abierta |
| `PATCH /team/members/{userId}` / `DELETE` | USERS_MANAGE | Cambia el rol / retira el acceso (nunca al dueño ni a uno mismo) |
| `GET /subscription` | USAGE_READ | Plan, estado (`TRIAL`, `ACTIVE`, `PAST_DUE`), vigencia y `onlinePayments` |
| `POST /subscription/checkouts` | BILLING_MANAGE | `{planCode, cycle}`: crea el pago en Mercado Pago y devuelve `checkoutUrl` (ADR-0024). 503 si el pago en línea no está configurado |
| `GET /subscription/checkouts/{id}` | USAGE_READ | Estado del pago (`PENDING`, `PAID`, `EXPIRED`) y `lastAttempt`; consulta a Mercado Pago si sigue pendiente |
| `POST /webhooks/mercadopago` | público (firma `x-signature`) | Aviso de Mercado Pago; el pago se consulta a su API |
| `POST /subscription/plan-change-requests` | BILLING_MANAGE | Elige plan y ciclo (`MONTHLY`/`ANNUAL`) desde la oferta de mejora; guarda el precio cotizado. El plan cambia al confirmarse el pago (ADR-0021) |
| `GET /subscription/plan-change-requests/pending` | USAGE_READ | Solicitud pendiente de pago, si la hay |
| `POST /pos-sales` | INVOICE_WRITE + INVOICE_FINALIZE | Venta rápida: crea y emite el POS en un paso. Exige `Idempotency-Key` (ADR-0018) |
| `GET/POST /payment-accounts` | POST: PROVIDER_MANAGE (solo dueño) | Conecta la cuenta Bold del comercio (`mode` TEST/LIVE, `identityKey`, `secretKey`). Verifica las llaves; devuelve los medios activos y, **una sola vez**, `webhookPath` (ADR-0020) |
| `POST /pos-charges` | INVOICE_WRITE + INVOICE_FINALIZE | Cobro con QR: verifica que el POS podrá emitirse, crea el borrador y un link de pago por el total (pesos enteros). Exige `Idempotency-Key`. Devuelve `checkoutUrl` para el QR |
| `GET /pos-charges/{id}` | INVOICE_FINALIZE | Estado del cobro (`PENDING`, `PAID`, `REJECTED`, `EXPIRED`, `CANCELLED`); consulta a Bold si sigue pendiente. Si está pagado, `sale` es el POS ya emitido, o `issueBlockedCode` dice por qué aún no |
| `POST /webhooks/bold/{token}` | público (token + firma `x-bold-signature`) | Aviso de Bold. Solo dispara la consulta de los cobros pendientes del comercio |

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

- Cabecera `Idempotency-Key` en los demás POST (hoy la exigen `/pos-sales` y `/pos-charges`).
- API keys aceptadas en las rutas (integración de AdoptaFácil).
- Usuarios e invitaciones (`/users`), gestión de API keys (`/api-keys`).
- Webhooks de proveedor (`/webhooks/{providerId}/{token}`); el núcleo y la verificación de firma ya existen.
- Notas crédito y documento soporte.
- Modo de sesión móvil (ADR-0015).
