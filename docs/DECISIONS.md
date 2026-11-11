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

**Decisión.** La plataforma no transmite directamente a la DIAN. Usa proveedores tecnológicos autorizados mediante adaptadores. Corrección 2026-11-05: muisca.io (Aliaddo SAS) sí es un proveedor tecnológico autorizado y es el recomendado como primer adaptador (ver PROVIDERS.md); MUISCA, el sistema de la DIAN, es otra cosa. El adaptador simulado jamás opera fuera de `SIMULATION`.

**Pendiente (bloqueo real, no técnico).** Confirmar con el proveedor elegido y con asesoría tributaria/legal que su contrato permite a un tercero operar emisión para múltiples contribuyentes, y bajo qué modalidad de credenciales.

## ADR-0009 — La factura queda ligada al ambiente y a la cuenta de proveedor al finalizarse
*2026-11-05*

**Contexto.** En la primera versión la cuenta se elegía al transmitir. Eso permitía numerar con una resolución de producción y transmitir por una cuenta de habilitación (o al revés), quemando números reales en pruebas.

**Decisión.** `Invoice.finalizeWith(range, account, …)` exige que resolución y cuenta sean del mismo tenant y del mismo ambiente, fija ambas en la factura, y rechaza cualquier evidencia de validación de un ambiente distinto (`CONFLICTING_PROVIDER_RESULT`). Las resoluciones de numeración llevan ambiente.

## ADR-0010 — Fechas de negocio en hora de Colombia
*2026-11-05*

**Decisión.** La fecha de emisión y los periodos se calculan con `ColombianTime.today(clock)` (`America/Bogota`), nunca con la zona del servidor. Un VPS en UTC emitiría con fecha del día siguiente entre las 19:00 y la medianoche.

## ADR-0011 — Medición de consumo y cuota
*2026-11-05*

- Cuenta documentos **emitidos** (numerados), no solo validados: cada número consume resolución y costo con el proveedor.
- Solo los de `PRODUCTION` consumen cuota; los de habilitación se registran para medir costo de pruebas; los simulados no se registran.
- Solo `TRIAL` y `ACTIVE` emiten; `PAST_DUE` y `CANCELLED` bloquean la emisión (confirmado por el propietario).
- Límite `NULL` = sin límite. Condiciones particulares (p. ej. un convenio) = sobrescrituras genéricas en la suscripción.
- La verificación bloquea la fila de suscripción en la transacción para que finalizaciones concurrentes no superen el límite.

## ADR-0012 — Repositorio público en GitHub con CI en GitHub Actions
*2026-11-05 — por decisión del propietario*

**Contexto.** El entorno de desarrollo no accede a Maven Central/npm/Docker Hub; GitHub sí es accesible. En repositorios públicos GitHub Actions no cobra minutos.

**Decisión.** CI en `.github/workflows/ci.yml`: `mvn verify` (JUnit real, Testcontainers) y migraciones + pruebas de aislamiento contra un PostgreSQL 16 de servicio. Los fallos se publican como anotaciones del check, legibles por API. Dependabot semanal para Maven y Actions.

**Consecuencias.** El código es visible para cualquiera. Aceptable porque no hay secretos en el repositorio y la seguridad no depende de ocultar el código; el repositorio no tiene licencia abierta (todos los derechos reservados). Nunca se suben `.env`, credenciales de proveedores ni datos de clientes.

## ADR-0013 — Persistencia con JDBC explícito (JdbcClient) en vez de JPA/Hibernate
*2026-11-05*

**Contexto.** El stack inicial proponía Spring Data JPA. El modelo de persistencia tiene RLS, triggers de inmutabilidad, FK compuestas, `jsonb`, funciones `SECURITY DEFINER` y asignación atómica de números.

**Problemas de JPA aquí.** Las entidades JPA tienden a convertirse en el modelo de dominio (lo que el proyecto prohíbe) o a duplicarlo; el *dirty checking* puede emitir `UPDATE` de columnas que los triggers declaran inmutables; el SQL generado es menos auditable para un sistema con RLS; consume más memoria y arranque.

**Decisión.** Adaptadores de repositorio con `JdbcClient` y SQL explícito, mapeando a/desde los agregados del dominio. Un único punto (`TenantTransactions`) abre la transacción y fija `app.tenant_id`.

**Costo.** Más código de mapeo manual; se compensa con pruebas de integración por adaptador contra PostgreSQL real.

## ADR-0014 — Planes aprobados y excedentes solo con aceptación expresa
*2026-11-05 — decisión del propietario*

- Planes: Micro ($69.000/año, **solo pago anual**, 15 docs/mes), Emprendedor ($12.900/mes, 40), Negocio ($29.900/mes, 120), Pro ($69.900/mes, 300). Pago anual = 10 meses. Usuarios ilimitados. Prueba gratis: 20 documentos en total, sin excedentes.
- La cuota es **por ciclo mensual** aunque el pago sea anual; los ciclos se anclan a la fecha de inicio de la suscripción.
- Al llegar al límite **no se cobra nada automáticamente**: la emisión responde `DOCUMENT_QUOTA_REACHED` y la interfaz ofrece mejorar el plan. Solo si el cliente acepta expresamente se emiten documentos adicionales a $150 c/u.
- La aceptación vale para esa suscripción y ese ciclo; al cambiar de plan o empezar un ciclo nuevo se vuelve a ofrecer la mejora antes de cobrar (supuesto: evita cargos sorpresa y empuja la mejora de plan). Queda auditada y con el precio congelado; cada documento excedente se registra con su precio en `usage_events`.

## ADR-0015 — Una sola aplicación web, instalable como PWA y empaquetable como app móvil
*2026-11-05 — por pedido del propietario*

**Contexto.** El propietario quiere que la aplicación web pueda convertirse fácilmente en app móvil.

**Decisión.** Un único frontend en React, diseñado *mobile-first*:
1. **PWA** (manifest + service worker): se instala desde el navegador en Android/iOS/escritorio, sin tiendas.
2. **Capacitor** cuando se quiera estar en Play Store / App Store: empaqueta el mismo build web en una app nativa; no se reescribe la interfaz.

Se descartan React Native o Flutter por ahora: obligan a mantener una segunda base de código de interfaz.

**Impacto en el backend.** Toda la funcionalidad ya está en la API REST, así que el backend no cambia de arquitectura. Lo único que cambia es la sesión. El refresh token web viaja en una cookie `HttpOnly; Secure; SameSite=Strict` con `Path=/api/v1/auth`, y dentro de un WebView nativo (origen `capacitor://localhost` / `https://localhost`) esa cookie no funciona de forma confiable. Al empaquetar la app nativa se agregará un **modo de sesión móvil**:
- el refresh token se entrega en el cuerpo de la respuesta solo cuando el cliente se identifica como app nativa;
- se guarda en el almacenamiento seguro del sistema (Keychain / Keystore);
- se envía en una cabecera;
- conserva la rotación y la revocación por familia que ya existen.

El modo web con cookie no cambia. Sigue pendiente en ROADMAP.

## ADR-0016 — Hosting del piloto: VPS en Hostinger, aislado de otros productos
*2026-11-05 — elección del propietario; condiciones técnicas propias*

**Decisión.** El piloto corre en un VPS Linux de Hostinger con Docker Compose, la misma composición que ya se valida en CI, detrás de un proxy con HTTPS.

**Condición.** Se recomienda un VPS **dedicado** a esta plataforma: maneja datos tributarios, y un incidente o una caída de otro producto no debe arrastrarla. Si al inicio comparte VPS con otro desarrollo del propietario, el aislamiento mínimo es:
- PostgreSQL propio, con roles propios y sin puerto público;
- red Docker propia;
- límites de CPU y memoria por contenedor;
- secretos propios;
- backups cifrados propios, fuera del VPS, con restauración probada.

Así la plataforma puede moverse a otro servidor sin cambios de código.

**Pendiente.** Elegir plan (RAM mínima sugerida: 4 GB si comparte, 2 GB si es dedicado; a verificar con medición real del consumo), dominio y política de backups. Detalle en DEPLOYMENT.md.

## ADR-0017 — Interfaz con React + Vite, servida por nginx en el mismo origen; pnpm
*2026-11-06*

**Decisión:**
- Una SPA en React 19 + TypeScript + Vite.
- Sin librería de componentes ni de estado: CSS propio con tokens y `fetch` envuelto. Menos dependencias significa menos superficie de ataque y menos peso en celulares de gama baja.
- Se sirve con nginx sin root, que también pasa `/api` al backend. Al estar en el mismo origen, la cookie de refresh `SameSite=Strict` funciona sin CORS.
- El gestor de paquetes es **pnpm**, por decisión del propietario y por seguridad:
  - lockfile obligatorio;
  - `minimumReleaseAge` de 7 días;
  - scripts de instalación de dependencias bloqueados salvo una lista revisada.

**Alternativas descartadas:**
- Next.js: necesita servidor Node y no aporta SEO a una app privada.
- Servir la interfaz desde Spring Boot: acopla los ciclos de despliegue.
- Una librería de UI completa: peso y dependencias sin necesidad.

**Consecuencia:** la app móvil (ADR-0015) empaqueta este mismo build.

## ADR-0018 — Comercio desde el lanzamiento: POS electrónico con venta rápida
*2026-11-06 — decisión del propietario; diseño técnico propio*

**Decisión:** entrar al mercado con comercio desde el día uno, no en una segunda etapa.

- **Planes** (V10): Comercio 500, 1.200 y 3.000, con pago anual de 11 meses y excedente de $60 por documento, solo con aceptación expresa.
- **Documento:** el documento equivalente POS comparte agregado y ciclo de vida con la factura y se distingue por `kind`. Tiene su propia resolución de numeración (V11).
- **Unicidad del número:** es única por tipo de documento y ambiente. Practicar en simulación con el prefijo real ya no bloquea ese número en producción; antes era un error latente.
- **Comprador:** un POS puede ir a consumidor final, con la identificación genérica `222222222222`. Una factura exige siempre un comprador identificado.
- **Venta rápida:** `POST /api/v1/pos-sales` crea y emite en un solo paso, con `Idempotency-Key` obligatoria. Si la caja reintenta tras un corte de red, recibe la misma venta y nunca una segunda. La misma clave con otro contenido devuelve 422.

**Alternativas descartadas:**
- Un agregado separado para el POS: duplicaba el ciclo de vida, la cola de envío y la conciliación.
- La emisión en dos llamadas desde la caja: dejaba borradores huérfanos y abría la puerta a cobrar dos veces.

## ADR-0019 — Fondo solidario de facturación (diseño; se implementa después de los pagos)
*2026-11-06 — por pedido del propietario*

**Propósito:** la razón social es la bandera. Una capa gratuita y completa para refugios y fundaciones, financiada por las suscripciones, con cifras abiertas.

**Diseño:**
- **Fondo:** se alimenta con un porcentaje fijo y publicado de cada mensualidad, a definir.
- **Uso:** paga los documentos de las organizaciones beneficiarias al costo real del paquete vigente (no a precio de lista).
- **Tope** por organización beneficiaria, para evitar abusos. Se verifica que sea ESAL en el RUT.
- **Panel de impacto** público, mes a mes: dinero que entró al fondo, documentos subsidiados, costo y organizaciones beneficiarias. Una organización solo aparece con su nombre si lo autorizó; si no, se cuenta sin nombre.
- **Para cada cliente:** ve cuánto aportó y cuántos documentos financió.
- **Consentimiento explícito** al suscribirse, con versión y fecha, igual que los términos (V9).

**Pendiente:** el porcentaje (decisión del propietario) y el tratamiento contable del aporte (contador).

## ADR-0020 — Cobro en tienda: cada comercio conecta su propia cuenta de pasarela
*2026-11-06 — aceptada; Bold implementado contra un servidor de pruebas local (falta probar con llaves de prueba reales)*

**Decisión de arquitectura:**
- La plataforma no recibe el dinero de las ventas del comercio. Cada comercio conecta su propia cuenta de pasarela; sus credenciales se guardan cifradas, como las de proveedor.
- La plataforma solo escucha el aviso de pago confirmado y emite el POS. Así no actuamos como agregador de pagos y no asumimos obligaciones reguladas sobre dinero de terceros.
- Mercado Pago sigue siendo la pasarela de **nuestras** suscripciones.

**Requisitos de la pasarela:** alta sin equipo comercial, cobro Bre-B por QR, webhooks firmados y consulta de estado por referencia.

**Candidata principal: Bold.**
- Alta autogestionada desde su panel de comercios.
- Webhooks firmados con HMAC-SHA256 y reintentos.
- Bre-B por QR en su link de pagos.
- **Riesgos:** el QR directo por API exige certificación previa del comercio, y el webhook puede tardar hasta 10 minutos. Por eso la confirmación también se consulta por referencia mientras la caja espera.

**Otras opciones:** ePayco anunció Bre-B para sus comercios; Kushki y EBANX lo tienen con documentación cerrada o enfocada en empresas grandes. Falta confirmar las tarifas del QR de cada una.

**Implementación (2026-11-06):**
- Módulo `payments` con puerto `MerchantPaymentGateway` y adaptador Bold (`BoldGateway`, cliente HTTP del JDK, sin SDK). `invoicing` no conoce a `payments` (regla de arquitectura `paymentsIsOneDirectional`).
- Se usa la **API de links de pago** de Bold (`POST /online/link/v1`, `GET /online/link/v1/{id}`), no el QR directo, porque no exige certificación previa. El QR que se muestra en caja es el del link; el checkout de Bold ofrece los medios que el comercio tenga activos (Bre-B incluido, según su panel).
- Montos del cobro en pesos enteros (redondeo HALF_UP sobre el total del POS).
- **La pasarela es la fuente de verdad.** El webhook no se cree: con firma válida solo dispara la consulta del estado a Bold. Tres caminos llegan al mismo `refresh` (pantalla de caja cada 2,5 s, webhook, conciliación cada 30 s de lo pendiente en 24 h); el cobro se bloquea (`FOR UPDATE`) y un estado final no cambia, así que el POS se emite una sola vez.
- **Antes de cobrar** se verifica, en una transacción corta, que el documento podrá salir (proveedor, suscripción, cupo, resolución POS). No se recibe un pago cuya venta no pueda documentarse.
- Si el pago se confirma pero el documento no puede salir (cupo agotado en ese instante, borrador editado después de cobrar), el cobro queda PAID, la venta en borrador y `issueBlockedCode` explica el motivo. Nunca se documenta un valor distinto al cobrado (`CHARGE_AMOUNT_MISMATCH`).
- Ambientes coherentes: llaves de PRUEBA solo con documentos de simulación/habilitación; llaves de PRODUCCIÓN solo con documentos en producción (`PAYMENT_ENVIRONMENT_MISMATCH`).
- URL de webhook por comercio con token opaco de 256 bits (se guarda su SHA-256) + firma `x-bold-signature` (HMAC-SHA256 del cuerpo en Base64 con la llave secreta; en pruebas, llave vacía). Cuerpo máximo 64 KB.
- **Riesgo aceptado para el piloto:** la creación del link se hace dentro de la transacción del borrador (hasta 10 s si Bold tarda). Si Bold falla, no queda nada a medias; si la base falla después, queda un link huérfano en Bold que nadie ve.
- **Pendiente:** probar con llaves de prueba reales de Bold (GitHub Secrets `BOLD_TEST_IDENTITY_KEY` / `BOLD_TEST_SECRET_KEY`) y confirmar los nombres exactos de los estados y del medio de pago Bre-B en sus respuestas; hoy se aceptan `PAID`/`APPROVED` como pagado y cualquier estado desconocido se trata como pendiente.

Fuentes: [Bold — webhooks](https://www.developers.bold.co/webhook), [integración Bold con QR Bre-B (código abierto)](https://github.com/hansellramos/cabania-app/pull/48), [ePayco + Bre-B](https://www.valoraanalitik.com/epayco-integra-bre-b-a-su-plataforma-y-habilita-pagos-inmediatos-para-mas-de-15-millones-de-comercios/).

## ADR-0021 — Oferta de mejora de plan antes de los excedentes
*2026-11-06 — aceptada*

**Contexto:** el propietario decidió que el excedente ($150 general, $60 comercio) solo aplica después de ofrecer subir de plan y con aceptación expresa (ADR-0014). El cobro automático de suscripciones con Mercado Pago todavía no está.

**Decisión:**
- Cuando la cuota llega al 80 % (inicio) o se agota al emitir o vender, la interfaz muestra primero los planes: desde la prueba, todos los pagos; desde un plan pago, los de su misma familia con más documentos. Cada plan muestra precio mensual y anual con los meses gratis.
- Elegir un plan crea una **solicitud** (`plan_change_requests`, V13) con el precio cotizado y el ciclo; una nueva reemplaza la pendiente. **El plan no cambia sin pago confirmado.** Para el piloto, el equipo envía el link de pago de Mercado Pago y activa el plan; después se automatiza con el webhook de Mercado Pago.
- El excedente aparece debajo, solo si el plan lo admite, con casilla de aceptación y botón separado. Solo dueño o administrador deciden; los demás roles ven a quién pedírselo.
- Lo que se estaba haciendo no se pierde: el borrador o el carrito siguen ahí y se emiten al resolverlo.

**Pendiente:** aviso al equipo de cada solicitud nueva (hoy se consulta en la base) y activación automática con Mercado Pago.

## ADR-0022 — Correo transaccional independiente del proveedor
*2026-11-10 — aceptada*

**Contexto:** recuperar contraseña e invitar al equipo necesitan correo. No hay dominio ni proveedor elegido todavía.

**Decisión:** puerto `Mailer` en `shared` y tres adaptadores por configuración (`billing.mail.mode`): `smtp` (producción; cualquier proveedor SMTP, Amazon SES como opción de menor costo), `log` (por omisión, no envía y no imprime enlaces) y `memory` (pruebas). Docker Compose trae Mailpit para desarrollo y las pruebas de navegador. El envío ocurre después de confirmar la transacción y en segundo plano (hilos virtuales): un correo nunca sale por una operación deshecha y el tiempo de respuesta no revela si una cuenta existe.

**Alternativas descartadas:** SDK de un proveedor (acopla y no aporta para este volumen); cola de correos en la base (innecesaria hoy; se reconsidera si se pierden correos o crece el volumen).

**Riesgo aceptado:** si el SMTP falla, el correo se pierde (queda registrado como advertencia). El usuario puede pedir otro enlace o reenviar la invitación.

## ADR-0023 — Identidad visual y página pública
*2026-11-10 — aceptada (pendiente el nombre comercial definitivo)*

- Tipografías propias servidas desde el mismo dominio (sin Google Fonts: privacidad y CSP estricta): Bricolage Grotesque para títulos y Public Sans para el texto y las cifras (números tabulares).
- Paleta: verde de aval `#0b7a5c` para acciones, noche `#12261f` para la estructura, sol `#f4c43b` solo para lo que nos mueve (fondo solidario), fondo `#f5f7f4`.
- Un solo elemento con movimiento en la página pública: el tiquete que "se imprime" cuando se confirma el pago, que es la promesa del producto. Respeta `prefers-reduced-motion`.
- La raíz `/` muestra la página pública sin sesión y el inicio de la app con sesión. Los precios se leen del catálogo (`/public/plans`), nunca se escriben a mano.
- Las pantallas de acceso muestran la misión a la izquierda en pantallas anchas.

**Revisión 2026-11-10 (pedido del propietario: landing "de talla nacional" con 3D y gráficas):**
- Hero oscuro con escena Three.js que cuenta la promesa: el celular corre la caja (venta → QR → pago) y el tiquete sale impreso con el sello "Emitido". Reemplaza al tiquete animado en CSS, que queda como versión estática.
- Presupuesto de rendimiento: la landing es un paquete aparte (7 KB gzip); Three.js (~130 KB gzip) se descarga después de pintar la página, en tiempo ocioso; Chart.js (~50 KB gzip) solo cuando la calculadora se acerca a la pantalla. Sin sombras dinámicas ni postprocesado, píxeles limitados a 1,75×, texturas de lienzo que solo se redibujan al cambiar de estado, bucle detenido fuera de pantalla o con la pestaña oculta. Sin WebGL, con ahorro de datos o con movimiento reducido: versión estática.
- Caja de demostración en la misma página (sin cuenta, sin llamadas al servidor) y calculadora de costo por documento con el catálogo real. La confianza se apoya en hechos verificables (estado ante la DIAN, ambientes, aislamiento, dinero directo al comercio), no en cifras inventadas ni en comparaciones con terceros sin fuente.

## Verificaciones normativas registradas

| Fecha | Tema | Resultado | Fuente |
|---|---|---|---|
| 2026-11-05 | Anexo Técnico FE vigente | Versión 1.9; Res. 000165/2023, obligatoria desde 2024-05-01 por Res. 000008/2024 | Sovos; normogramas oficiales Res. 0008/2024 |
| 2026-11-05 | Tarifa general de IVA | 19% (ET art. 468). Hay un proyecto de reforma en trámite (oct-2026) que mueve bienes entre tarifas; no aprobado | Vanguardia, 30-oct-2026 |
| 2026-11-05 | Vías para facturar | Solución gratuita DIAN, desarrollo propio, proveedor tecnológico | micrositios.dian.gov.co |
