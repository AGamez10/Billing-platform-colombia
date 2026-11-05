# Arquitectura

> Estado verificado al 2026-11-05. Este documento describe el código que existe; lo planeado está marcado como tal.

## Estilo

Monolito modular con puertos y adaptadores (hexagonal). Un solo artefacto desplegable, un solo PostgreSQL, límites de módulo explícitos y verificados por pruebas (`ArchitectureRulesTest`). Justificación en [DECISIONS.md](DECISIONS.md) (ADR-0001).

```
backend/src/main/java/co/billingplatform/
├── shared/
│   ├── domain               TenantId, Party, PartyIdentification (NIT+DV), RoundingPolicy, Actor, ColombianTime
│   └── application          AuditTrail (puerto)
├── identity/
│   ├── domain               Role, Permission, PasswordPolicy, LoginThrottlePolicy, OpaqueTokens
│   └── application          AuthenticationService, RefreshTokenService, ApiKeyService, IdentityPorts
├── subscriptions/
│   ├── domain               Subscription (límites del plan + sobrescrituras)
│   └── application          SubscriptionUsageMeter (implementa UsageMeter de facturación)
├── tax/domain               TaxRate (con vigencia y fundamento legal), TaxCatalog, Discount, DocumentCalculator
├── invoicing/
│   ├── domain               Invoice (agregado + máquina de estados), InvoiceLine, NumberingRange, DocumentNumber
│   └── application          InvoiceDraftService, FinalizeInvoiceService, SubmitInvoiceService, ProviderWebhookService;
│                            puertos InvoiceRepository, NumberingRangeRepository, CustomerDirectory,
│                            SubmissionQueue (outbox), UsageMeter, IssuerDirectory
└── electronicinvoicing/     Módulo de contrato con proveedores tecnológicos
    ├── domain               ElectronicDocument (modelo neutral), SubmissionResult, ValidationEvidence, ProviderCapabilities…
    ├── port                 ElectronicInvoicingProvider, ProviderAccountRepository, SecretResolver, ProcessedEventStore
    ├── application          ProviderRegistry (enrutamiento por configuración + verificación de capacidades)
    └── adapter/
        ├── common           HmacWebhookVerifier
        └── mock             MockElectronicInvoicingProvider (solo SIMULATION)
├── platform/                Composición Spring: TenantTransactions, SecurityConfiguration (todo cerrado salvo
│                            lo público), errores RFC 9457, PublicCatalogController
├── tax/adapter/persistence  JdbcTaxCatalog
└── subscriptions/adapter/persistence  JdbcPlanCatalog
backend/src/main/resources/db/migration   V1–V6 (Flyway)
backend/src/main/resources/static         Página de estado local
backend/Dockerfile, docker-compose.yml     Entorno local completo
infrastructure/postgres/init               roles de base de datos
```

Planeado (aún sin código, requiere el build con red — ver [ROADMAP.md](ROADMAP.md)): `organizations`, `customers`, `products` (CRUD), capa web REST, adaptadores JPA/JDBC y criptográficos (Argon2id, JWT), worker de envíos, frontend React.

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
6. `invoicing` no depende de `subscriptions` ni de `identity` (los usa solo mediante puertos que él declara).

## Flujo de una factura

1. **Borrador** — `Invoice.draft(...)`: el motor (`DocumentCalculator`) calcula totales; el cliente HTTP nunca envía totales.
2. **Finalizar** — `FinalizeInvoiceService`: verifica cuota, toma la cuenta de proveedor activa y bloquea la resolución de su mismo ambiente; `finalizeWith(range, account, issueDate, …)` recalcula con las tarifas vigentes en la fecha de emisión (hora de Colombia), asigna número, fija cuenta y ambiente, y congela el contenido. Repetir la llamada no hace nada. En base de datos la asignación es atómica (`allocate_document_number`, probada con 500 asignaciones concurrentes sin duplicados) y el job de envío se inserta en la misma transacción (outbox).
3. **Transmitir** — `SubmitInvoiceService.submit(tenant, id)`, invocado por el worker. Resuelve la cuenta de proveedor del tenant, verifica capacidades, envía el `ElectronicDocument` neutral con una clave de idempotencia estable por factura y aplica el `SubmissionResult` normalizado.
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

PostgreSQL 16+, esquema compartido con `tenant_id` y Row-Level Security (ver [MULTITENANCY.md](MULTITENANCY.md)). Invariantes de negocio críticas duplicadas como restricciones de base de datos: no hay `ACCEPTED` sin evidencia, un borrador no tiene número, facturas numeradas inmutables, líneas cuadradas, totales cuadrados, tarifas sin solapamiento de vigencias, auditoría de solo inserción.
