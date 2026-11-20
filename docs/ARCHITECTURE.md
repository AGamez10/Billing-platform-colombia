# Arquitectura

> Estado verificado al 2026-11-19. Este documento describe el código que existe; lo planeado está marcado como tal.

## Estilo

Monolito modular con puertos y adaptadores (hexagonal). Un solo artefacto desplegable, un solo PostgreSQL, límites de módulo explícitos y verificados por pruebas (`ArchitectureRulesTest`). Justificación en [DECISIONS.md](DECISIONS.md) (ADR-0001).

Cada módulo sigue `domain` (reglas puras) → `application` (casos de uso y puertos) → `adapter/{persistence,web,worker,http,crypto,…}`. `platform/` compone todo con Spring.

```
backend/src/main/java/co/billingplatform/
├── shared/               TenantId, Party, FiscalProfile, FiscalAddress, OpaqueTokens, Csv, AuditTrail, Mailer, EmailHtml
├── identity/             Usuarios, sesiones, roles y permisos, equipo e invitaciones, claves de API, datos del
│                         emisor, verificación en dos pasos (Totp, RecoveryCodes, TwoFactorService)
├── customers/ products/  Clientes y productos (con importación CSV)
├── tax/                  Tarifas con vigencia y fundamento legal, DocumentCalculator (BigDecimal)
├── invoicing/            Factura, POS y nota crédito (agregado + máquina de estados), numeración, emisión,
│                         representación gráfica, enlaces para el comprador, Idempotency-Key
├── electronicinvoicing/  Contrato neutral con proveedores tecnológicos; ProviderRegistry; adaptador mock (SIMULATION)
├── subscriptions/        Planes, cuota, excedentes con consentimiento, cambio de plan, pago con Mercado Pago
├── payments/             Cobro con QR en la pasarela del comercio (Bold)
├── insights/             Lectura: inicio accionable (AttentionRules), avisos por correo, reportes
├── integrations/         Webhooks salientes firmados (outbox + SSRF guard)
└── platform/             Spring: seguridad, TenantTransactions (RLS), errores RFC 9457, cifrado, correo, configuración
backend/src/main/resources/db/migration   V1–V21 (Flyway)
frontend/                                  React 19 + Vite (PWA), e2e con Playwright
deploy/                                    Producción: Caddy, compose, copias cifradas, scripts (ADR-0026)
```

## Dirección de dependencias

```
            invoicing ──▶ tax
                │
                ▼
     electronicinvoicing (domain/port/application)  ◀── adapter/mock, adapter/<proveedor>
                │
                ▼
             shared
```

Reglas verificadas automáticamente:

1. `domain`, `application` y `port` no importan Spring, JPA, Hibernate, Servlet ni Jackson.
2. Nada fuera de `electronicinvoicing/adapter` importa un adaptador concreto.
3. `electronicinvoicing` no conoce `invoicing` ni `tax` (es un contrato publicado; el núcleo lo usa, no al revés).
4. `tax` no depende de `invoicing` ni de proveedores.
5. Ningún archivo de `main` usa `float`/`double`.
6. `invoicing` no depende de `subscriptions`; su núcleo (`domain`/`application`) tampoco de `identity`. Los usa solo mediante puertos que él declara. Su capa web sí exige permisos, como la de todos los módulos.
7. Cada adaptador de proveedor registra su propio bean (`MockProviderConfiguration`); la configuración central solo conoce el puerto.

## Flujo de una factura

1. **Borrador** — `Invoice.draft(...)`: el motor (`DocumentCalculator`) calcula totales; el cliente HTTP nunca envía totales.
2. **Finalizar** — `FinalizeInvoiceService`: verifica cuota, toma la cuenta de proveedor activa y bloquea la resolución de su mismo ambiente; `finalizeWith(range, account, issueDate, …)` recalcula con las tarifas vigentes en la fecha de emisión (hora de Colombia), asigna número, fija cuenta y ambiente, y congela el contenido. Repetir la llamada no hace nada. En base de datos la resolución se lee con `SELECT … FOR UPDATE`, así que dos emisiones concurrentes se serializan; el índice único `(tenant_id, prefix, number)` es la última barrera contra duplicados. El job de envío se inserta en la misma transacción (outbox). La función `allocate_document_number` sigue disponible y probada con 500 asignaciones concurrentes, pero la aplicación usa el bloqueo de fila para que la regla de vigencia y agotamiento viva en el dominio.
3. **Transmitir** — `SubmitInvoiceService.submit(tenant, id)`, invocado por `SubmissionWorker`. El worker reclama jobs con `claim_submission_jobs` (SKIP LOCKED + lease de 120 s, sin tenant) y procesa cada uno en su propia transacción con el tenant del job. Resultado: estado final → `DONE`; sin estado final → reintento con espera 10 s, 20 s, 40 s… hasta 30 min; error de negocio → `DEAD`, que requiere una persona. Resuelve la cuenta de proveedor del tenant, verifica capacidades, envía el `ElectronicDocument` neutral con una clave de idempotencia estable por factura y aplica el `SubmissionResult` normalizado.
4. **Resultado** — por respuesta directa, consulta de estado o webhook verificado (`ProviderWebhookService`). Solo `Accepted` con `ValidationEvidence` lleva a `ACCEPTED`; `isLegallyValid()` exige además ambiente `PRODUCTION`.

## Anti-duplicación (resultado incierto)

| Situación | Comportamiento |
|---|---|
| Falla antes de que el proveedor reciba (conexión rechazada) | Sigue `PENDING_SUBMISSION`, se reintenta |
| Timeout tras posible recepción | `SUBMITTED` + `outcomeUncertain` |
| Reintento incierto, proveedor idempotente | Reenvía con la misma clave (el proveedor deduplica) |
| Reintento incierto, proveedor con búsqueda por número | Busca primero; reenvía solo si no existe |
| Reintento incierto, proveedor sin ninguna de las dos | `RECONCILIATION_REQUIRED`: no reenvía a ciegas |
| Excepción inesperada del adaptador | Tratada como resultado incierto |
| El tenant cambia de proveedor con facturas en curso | Las facturas en curso siguen en la cuenta original |

## Persistencia

Adaptadores con `JdbcClient` y SQL explícito (ADR-0013). La factura se reconstruye desde la base con `Invoice.restore`, que recalcula los totales y falla si no coinciden con los guardados. Las lecturas para modificar usan `FOR UPDATE`; las de consulta (`findForRead`, `JdbcInvoiceQueries`) no bloquean.


PostgreSQL 16+, esquema compartido con `tenant_id` y Row-Level Security (ver [MULTITENANCY.md](MULTITENANCY.md)). Invariantes de negocio críticas duplicadas como restricciones de base de datos: no hay `ACCEPTED` sin evidencia, un borrador no tiene número, facturas numeradas inmutables, líneas cuadradas, totales cuadrados, tarifas sin solapamiento de vigencias, auditoría de solo inserción.
