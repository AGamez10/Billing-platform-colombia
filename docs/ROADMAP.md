# Hoja de ruta

## Bloqueos activos

| Bloqueo | Impacto | Cómo se desbloquea |
|---|---|---|
| Credenciales de sandbox del proveedor | No hay adaptador real | Proveedor recomendado: Muisca (PROVIDERS.md). Falta confirmar en su documentación si cubre POS y documento soporte, y crear la cuenta de pruebas |
| Datos legales de la empresa y revisión de abogado | Términos y política no se pueden publicar | El propietario completa los datos entre corchetes y un abogado revisa los borradores |

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

## Hecho (2026-11-05, quinta sesión)

- Registro de organización (con prueba gratis automática) e inicio de sesión por API: Argon2id, JWT de 15 min, refresh rotatorio en cookie segura con detección de reúso, bloqueo por cuenta, límite por IP, `/api/v1/auth/me`. Swagger con botón Authorize. CI: 105 pruebas + Docker con registro y sesión de punta a punta.

## Hecho (2026-11-05, sexta sesión)

- Clientes y productos por API (`/api/v1/customers`, `/api/v1/products`): permisos por rol desde el token, bloqueo optimista con `version`, búsqueda paginada con comodines escapados, desactivación en vez de borrado, auditoría en `audit_log`, validación de tarifas vigentes. CI: 113 pruebas en verde, incluidas pruebas HTTP de aislamiento entre organizaciones.

## Hecho (2026-11-05, séptima sesión)

- **Facturas por API, de punta a punta con el proveedor simulado**:
  - borrador con totales calculados por el servidor;
  - edición y borrado de borradores;
  - emisión idempotente con número consecutivo de la resolución vigente del mismo ambiente;
  - cola de envíos (outbox) procesada por un worker con reintentos exponenciales;
  - consulta con evidencia, historial y `legallyValid`;
  - listado paginado.
- Resoluciones de numeración, cuenta de proveedor (hoy solo `mock` en `SIMULATION`), consulta de cuota y aceptación expresa de excedentes (`BILLING_MANAGE`, solo dueño/admin).
- V8: datos para reconstruir la factura desde la base y verificar que los totales coincidan al leerla; ambiente obligatorio en facturas numeradas.
- CI: **116 pruebas Java en verde**. Incluyen la prueba HTTP que cubre registro → proveedor → resolución → cliente → borrador → emisión → aceptada `SIMULATED-` con `legallyValid=false`, numeración SETT1/SETT2, inmutabilidad y aislamiento entre organizaciones. También pasan las pruebas SQL (dos invariantes nuevas) y Docker.
- Borradores de Términos y Condiciones y de Política de Tratamiento de Datos (documentos para revisión del abogado).
- ADR-0015 (web → PWA → Capacitor) y ADR-0016 (VPS Hostinger aislado).

## Hecho (2026-11-06)

- **Interfaz web** (React 19 + Vite, mobile-first, PWA instalable) en `frontend/`:
  - registro con validación del DV del NIT;
  - inicio con pasos de configuración y consumo;
  - clientes y productos;
  - facturas: borrador, totales calculados por el servidor, emisión y estado en vivo;
  - ajustes: modo simulación y resoluciones.
- Pruebas: Vitest para lógica y componentes, y Playwright contra el stack completo (CI).
- pnpm con controles de cadena de suministro (ADR-0017).
- **Aceptación legal registrada** en el alta (V9 `legal_acceptances`): versión vigente de términos y de política, usuario y fecha. Solo se permite insertar. Una versión distinta a la vigente se rechaza con `LEGAL_ACCEPTANCE_REQUIRED`. Las versiones vienen de `BILLING_TERMS_VERSION` y `BILLING_PRIVACY_VERSION` (hoy `borrador-2026-11`).

## Hecho (2026-11-06, comercio)

- Planes de comercio en el catálogo (V10), POS electrónico con numeración propia (V11), venta rápida idempotente (`/api/v1/pos-sales`) y pantalla **Vender** para celular (ADR-0018).
- Corregido: el número del documento ahora es único por tipo y ambiente.

## Hecho (2026-11-06, cobro con QR)

- **"Cobra y la factura sale sola"** con la cuenta Bold de cada comercio (ADR-0020): V12 (`payment_accounts`, `charges`), almacén de secretos AES-256-GCM, API de conexión y de cobro, webhook firmado, conciliación periódica y pantallas en Ajustes y Vender (QR en SVG).
- Pruebas: Bold simulado localmente (adaptador y API de punta a punta), 22 pruebas SQL nuevas de aislamiento e invariantes, prueba del componente QR.
- Falta: probar con llaves de prueba reales de Bold.
- **Oferta de mejora de plan** (ADR-0021): al 80 % de la cuota o al agotarla, se ofrecen los planes y luego, si aplica, los excedentes con aceptación expresa. La elección queda registrada con su precio (V13); el plan se activa al pagar.

## Producto: lo que el mercado ya tiene + lo nuestro

| Función | Estado | Por qué |
|---|---|---|
| Facturar por WhatsApp (texto y nota de voz con IA) | Pendiente | Lo ofrece Siigo; hay que tenerlo |
| Asistente con IA dentro de la app | Pendiente | Lo ofrece Alegra; hay que tenerlo |
| "Cobra y la factura sale sola" (Bre-B + POS automático) | Hecho con Bold simulado; falta probar con llaves reales | Diferenciador |
| Billetera del comprador: compras deducibles (1 %) | Por validar con contador | Diferenciador y efecto red |
| Habilitación DIAN asistida (set de pruebas automático) | Por confirmar con Muisca | Diferenciador |
| Fondo solidario con panel de impacto | Diseño (ADR-0019) | Bandera de la marca |
| Nunca dejar de vender (contingencia, segundo proveedor) | Contingencia hecha; segundo proveedor pendiente | Diferenciador |

## Para salir con clientes piloto (orden recomendado)

1. ~~Oferta de mejora de plan y aceptación de excedentes desde la interfaz~~ (hecho, ADR-0021). Falta el cobro automático con Mercado Pago.
2. **Segmento y planes** (decisión del propietario pendiente): foco inicial en fundaciones, profesionales, servicios y facturación entre empresas; comercio y restaurantes (POS de alto volumen) solo con costo por documento ≤ $30 o con otro proveedor (PRICING.md).
3. **Datos fiscales completos** de emisor y adquiriente que exige el proveedor real: tipo de persona, régimen, responsabilidades, dirección con municipio DANE.
4. **Adaptador Muisca** en `Test` y luego en `Habilitation`, cuando lleguen las credenciales (GitHub Secrets); secretos cifrados (AES-GCM) en `provider_secrets`.
5. **Descarga de documentos** (PDF/XML del proveedor) y envío por correo al adquiriente.
6. **Despliegue** en VPS: dominio, HTTPS, backups cifrados fuera del VPS con restauración probada, monitoreo básico.
7. **Landing page** y página de precios.
8. Cobro de suscripción con Mercado Pago (para el piloto basta un link de pago manual).

Riesgos conocidos del piloto:
- El worker llama al proveedor dentro de la transacción que bloquea la factura. Es aceptable con este volumen y se separará antes de escalar.
- El proveedor simulado guarda su estado en memoria; un reinicio en medio de un envío *pendiente* lo deja sin referencia. No afecta producción.

## Después del MVP

- Notas crédito y débito (el contrato y la numeración ya contemplan `CREDIT_NOTE`/`DEBIT_NOTE`); flujo de corrección y reenvío tras rechazo, a confirmar con el proveedor.
- Retenciones (ReteFuente, ReteIVA, ReteICA) con reglas verificadas por un contador.
- Dirección, municipio, régimen y responsabilidades fiscales de las partes según el Anexo Técnico.
- Recuperación de contraseña, verificación de correo, MFA, invitaciones.
- Suite de pruebas de contrato compartida que cada adaptador de proveedor debe pasar.
- Pasarela de pagos para suscripciones.
- Evaluar Java 25 LTS.
