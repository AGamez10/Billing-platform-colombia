# Registro de decisiones (ADR)

Formato: contexto → decisión → consecuencias. Estado: Aceptada salvo que se indique.

---

## ADR-0001 — Monolito modular hexagonal, un módulo Maven, paquetes por módulo de negocio
*2026-11-05*

**Contexto.** La propuesta inicial listaba 14 módulos (`identity`, `tenants`, `organizations`, `users`, `integrations/electronicinvoicing`…). Equipo pequeño, plazo de días, sin carga real aún.

**Decisión.**
- Un solo artefacto Spring Boot; módulos como paquetes de primer nivel con subpaquetes `domain`/`application`/`port`/`adapter`.
- Se fusionan conceptos duplicados: `tenants` + `organizations` (una organización *es* el tenant) y `users` + `identity`.
- `integrations/electronicinvoicing` pasa a módulo de primer nivel `electronicinvoicing`: "integrations" agrupa por tecnología, no por negocio.
- Reglas de dependencia verificadas por prueba (`ArchitectureRulesTest`; ArchUnit cuando haya acceso a Maven Central).
- Maven sobre Gradle: más simple de auditar, estándar de Spring Initializr, sin ventaja relevante de Gradle a esta escala.

**Consecuencias.** Despliegue y operación baratos (un proceso + PostgreSQL). Extraer un módulo a servicio sigue siendo posible porque los puertos ya son el contrato.

## ADR-0002 — Multi-tenancy: esquema compartido + `tenant_id` + Row-Level Security
*2026-11-05*

**Decisión.** Ver [MULTITENANCY.md](MULTITENANCY.md). Rol de aplicación sin ownership ni `BYPASSRLS`; tenant local a la transacción; FK compuestas; funciones `SECURITY DEFINER` acotadas para flujos pre-tenant.

**Alternativas.** Esquema por tenant (migraciones × N, más conexiones); base por tenant (costo prohibitivo para microempresas). Se reconsidera solo para clientes con exigencia contractual de aislamiento físico.

## ADR-0003 — Autenticación: JWT de acceso corto + refresh opaco rotatorio + API keys
*2026-11-05 — diseñada, sin código*

**Contexto.** La propuesta pedía "JWT". Un JWT de larga duración no se puede revocar, y en `localStorage` queda expuesto a XSS.

**Decisión.** Access JWT de 15 min (resource server de Spring, sin librerías JWT de terceros); refresh token opaco rotatorio en cookie `HttpOnly/SameSite=Strict` con detección de reúso; Argon2id; API keys hasheadas con scopes para integraciones máquina-a-máquina. Detalle en [SECURITY.md](SECURITY.md).

## ADR-0004 — Contrato de proveedores revisado
*2026-11-05*

**Problemas de la interfaz propuesta.**
1. `getInvoiceStatus(String externalId)` sin cuenta: el adaptador no sabría qué credenciales ni ambiente usar.
2. `handleWebhook(...)` con retorno `void`: el adaptador tendría que cambiar estados del dominio, invirtiendo la dependencia; además no hay verificación explícita.
3. Métodos separados por tipo de documento multiplican la interfaz y no expresan capacidades parciales.
4. Respuestas por tipo de proveedor sin un modelo cerrado de resultados → manejo de errores inconsistente.
5. Sin idempotencia ni manejo de resultados inciertos → riesgo de facturas duplicadas.

**Decisión.** Interfaz en [PROVIDERS.md](PROVIDERS.md): cuenta explícita, `submit` único con `IdempotencyKey`, `parseWebhook` que solo verifica y traduce, resultado sellado `SubmissionResult`, `ProviderCapabilities` consultadas por `ProviderRegistry`.

## ADR-0005 — Cálculos monetarios
*2026-11-05*

**Decisión.** `BigDecimal` en todo el núcleo (regla automatizada que prohíbe `float`/`double`). Política `RoundingPolicy(2, HALF_UP)` aplicada por línea: base = round(bruto − descuento); impuesto = round(base × tarifa); totales = suma de valores ya redondeados (cuadran exactamente; propiedad probada con 500 documentos aleatorios). El descuento informado se deriva como bruto redondeado − base para que bruto − descuento = base siempre.

**Pendiente.** Contrastar con las reglas de validación y tolerancias del Anexo Técnico 1.9 y con el proveedor elegido; la política es inyectable para ajustarla sin tocar el motor. Retenciones, cargos/descuentos globales e impuestos de valor fijo quedan fuera del MVP.

## ADR-0006 — Envíos asíncronos con outbox en PostgreSQL, sin broker
*2026-11-05*

**Decisión.** Tabla `submission_jobs` insertada en la misma transacción que finaliza la factura; worker en el mismo proceso que reclama con `FOR UPDATE SKIP LOCKED` (`claim_submission_jobs`) con *lease*; backoff exponencial calculado por la aplicación.

**Por qué no RabbitMQ/Kafka/Redis.** Volumen esperado de miles de documentos/día, no millones; un broker añade costo, operación y otro punto de falla sin beneficio hoy. Revisar si la cola supera ~50 documentos/s sostenidos o si se separan procesos.

## ADR-0007 — Java 21 y Spring Boot 4.1
*2026-11-05*

**Contexto.** Spring Boot 3.5 terminó soporte OSS el 2026-06-30; 4.0.x termina el 2026-12-31; 4.1 tiene soporte estimado hasta 2027-07-31.

**Decisión.** Spring Boot 4.1.x. Java 21 LTS (el que está disponible en el entorno); evaluar Java 25 LTS una vez exista el build completo con red. Versión exacta del parche pendiente de fijar al primer build (POM aún no resuelto).

## ADR-0008 — Estrategia regulatoria: SaaS integrador sobre proveedores autorizados
*2026-11-05*

**Decisión.** La plataforma no transmite directamente a la DIAN. Usa proveedores tecnológicos autorizados mediante adaptadores. Se descarta un adaptador "Muisca" (es el sistema de la DIAN, no un proveedor; ver PROVIDERS.md). El adaptador simulado jamás opera fuera de `SIMULATION`.

**Pendiente (bloqueo real, no técnico).** Confirmar con el proveedor elegido y con asesoría tributaria/legal que su contrato permite a un tercero operar emisión para múltiples contribuyentes, y bajo qué modalidad de credenciales.

## Verificaciones normativas registradas

| Fecha | Tema | Resultado | Fuente |
|---|---|---|---|
| 2026-11-05 | Anexo Técnico FE vigente | Versión 1.9; Res. 000165/2023, obligatoria desde 2024-05-01 por Res. 000008/2024 | Sovos; normogramas oficiales Res. 0008/2024 |
| 2026-11-05 | Tarifa general de IVA | 19% (ET art. 468). Hay un proyecto de reforma en trámite (oct-2026) que mueve bienes entre tarifas; no aprobado | Vanguardia, 30-oct-2026 |
| 2026-11-05 | Vías para facturar | Solución gratuita DIAN, desarrollo propio, proveedor tecnológico | micrositios.dian.gov.co |
