# Hoja de ruta

## Bloqueos activos

| Bloqueo | Impacto | Cómo se desbloquea |
|---|---|---|
| Credenciales de sandbox del proveedor | No hay adaptador real | Proveedor recomendado: Muisca (PROVIDERS.md). Falta confirmar en su documentación si cubre POS y documento soporte, y crear la cuenta de pruebas |
| Precios y límites de planes | La tabla `plans` existe sin datos | Propuesta en PRICING.md, pendiente de aprobación del propietario |

## Hecho (2026-11-05, con pruebas ejecutadas)

- Núcleo de dominio sin frameworks: motor tributario con vigencias, factura con máquina de estados, numeración, contrato de proveedores, registro/enrutamiento, adaptador simulado, envío idempotente con manejo de resultados inciertos, webhooks verificados.
- 53 pruebas Java (runner offline compatible con JUnit 5) y 49 pruebas SQL de aislamiento e invariantes contra PostgreSQL 16; prueba de concurrencia de numeración (500 asignaciones paralelas, 0 duplicados).
- Esquema completo V1–V5: identidad, organizaciones, membresías, API keys, catálogo, clientes, productos, numeración, facturas, cuentas de proveedor, secretos, outbox, webhooks, idempotencia de API, planes, suscripciones, consumo y auditoría.

## Hecho (2026-11-05, segunda sesión)

- Casos de uso `InvoiceDraftService` (crear, editar, borrar borradores) y `FinalizeInvoiceService` (cuota, resolución bloqueada del mismo ambiente, número, outbox, consumo, auditoría; idempotente).
- Factura ligada a ambiente y cuenta de proveedor al finalizar (ADR-0009); fechas en hora de Colombia (ADR-0010).
- Módulo `subscriptions`: suscripción con límites efectivos y `SubscriptionUsageMeter` (ADR-0011).
- Módulo `identity` (núcleo): política de contraseñas, login con anti-enumeración y bloqueo progresivo, refresh tokens rotatorios con detección de reúso, API keys con scopes no administrativos, roles con prevención de escalada.
- CI de GitHub Actions y Dependabot, listos para el primer push.
- Pruebas: 82 Java y 49 SQL, todas pasan.

## Hecho (2026-11-05, tercera sesión)

- Código en GitHub (`AGamez10/billing-platform-colombia`, público). **Primer CI real en verde**: Spring Boot 4.1.0 resuelve y compila; Surefire ejecutó 84 pruebas en 15 clases (0 fallos), incluida la de arranque con PostgreSQL en Testcontainers (Flyway migra como `billing_owner`, la app opera como `billing_app`); las 49 pruebas SQL de aislamiento pasan contra PostgreSQL 16 de servicio.
- Arranque Spring: `TenantTransactions`, errores RFC 9457, configuración por variables de entorno; JDBC en vez de JPA (ADR-0013).
- Suscripción con pago vencido ya no emite documentos.
- Comparación pública de proveedores y propuesta de planes (PROVIDERS.md, PRICING.md).

## Hecho (2026-11-05, cuarta sesión)

- Planes aprobados en base de datos (V6) y excedentes solo con aceptación expresa (ADR-0014).
- Entorno local con Docker: `docker compose up --build` levanta PostgreSQL + backend con página de estado, catálogo público (planes, tarifas) y OpenAPI. Seguridad cerrada por defecto. CI verifica la imagen y el stack de punta a punta: 95 pruebas Java, pruebas SQL y job Docker en verde.

## Siguiente — orden recomendado

1. **Build**: agregar ArchUnit a las reglas de arquitectura; seguridad de Spring configurada explícitamente.
2. **Persistencia**: adaptadores JDBC/JPA de los puertos existentes + aspecto transaccional que fija `app.tenant_id` desde el principal autenticado; Testcontainers corriendo `tenant_isolation_test.sql`.
3. **Identidad (capa web)**: Argon2id real, JWT de acceso, endpoints de registro/login/refresh/logout, gestión de membresías y API keys, rate limiting por IP. (El núcleo ya existe y está probado.)
4. **API REST v1**: clientes, productos, resoluciones de numeración, facturas (borrador → finalizar → estado), cuentas de proveedor, webhooks; OpenAPI; `Idempotency-Key`.
5. **Worker de envíos** con backoff y *lease*; métricas.
6. **Frontend** React + TypeScript + Vite + Tailwind: login, panel, clientes, productos, facturación, historial, configuración, consumo.
7. **Docker Compose** completo y guía de despliegue en VPS con backups.

## Después del MVP

- Notas crédito y débito (el contrato y la numeración ya contemplan `CREDIT_NOTE`/`DEBIT_NOTE`); flujo de corrección y reenvío tras rechazo, a confirmar con el proveedor.
- Retenciones (ReteFuente, ReteIVA, ReteICA) con reglas verificadas por un contador.
- Dirección, municipio, régimen y responsabilidades fiscales de las partes según el Anexo Técnico.
- Recuperación de contraseña, verificación de correo, MFA, invitaciones.
- Suite de pruebas de contrato compartida que cada adaptador de proveedor debe pasar.
- Pasarela de pagos para suscripciones.
- Evaluar Java 25 LTS.
