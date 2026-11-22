# Seguridad

> **Estado al 2026-11-19.**
> - **Implementado** significa con código y pruebas que corren en CI en cada cambio.
> - Nada aquí es una garantía de que el sistema sea impenetrable. Es el inventario de controles verificables y de riesgos conocidos, como punto de partida para una auditoría externa.

## 1. Guía rápida para auditar

| Qué | Dónde |
|---|---|
| Arquitectura y módulos | `docs/ARCHITECTURE.md`, `ArchitectureRulesTest` (dependencias prohibidas entre capas) |
| Aislamiento entre organizaciones | `docs/MULTITENANCY.md`, `backend/src/test/sql/tenant_isolation_test.sql` (RLS probado como el rol real de la app) |
| Superficie HTTP | `SecurityConfiguration` (rutas públicas explícitas), `AuthRateLimitFilter`, `ProblemDetailsHandler`, `docs/API.md` |
| Rutas públicas sin sesión | registro, login (incluido el segundo paso), refresh y logout, recuperar contraseña, invitaciones, `/api/v1/public/**` (planes, textos legales, documento compartido, plantillas CSV), webhooks de Bold y Mercado Pago. `/actuator` cerrado desde Caddy. |
| Secretos y criptografía | `AesGcmSecretVault`, `AesGcmSecretSealer`, `OpaqueTokens`, `Argon2PasswordHasher`, `AccessTokens` |
| Proxy y despliegue | `deploy/Caddyfile`, `frontend/nginx.conf`, `deploy/compose.prod.yml`, `docs/DEPLOYMENT.md` |
| Análisis automáticos | `.github/workflows/security.yml` (CodeQL, gitleaks, OSV-Scanner), `.github/dependabot.yml` |
| Decisiones | `docs/DECISIONS.md` (ADR-0001…0035) |

## 2. Modelo de amenazas (resumen)

**Activos:**
- datos tributarios de los clientes de cada organización (identificaciones, direcciones, montos);
- la capacidad de emitir documentos con validez ante la DIAN a nombre de una organización;
- credenciales guardadas (pasarelas, proveedor, webhooks);
- cuentas de usuario;
- el cobro de nuestros planes.

**Actores:**
- **Externo anónimo:** fuerza bruta, enumeración, abuso de rutas públicas, SSRF por webhooks.
- **Usuario de otra organización:** IDOR/BOLA, escalada entre tenants.
- **Usuario de la misma organización con un rol menor:** escalada de rol.
- **Integración con clave de API filtrada.**
- **Atacante con la contraseña de un usuario** (phishing, reutilización).
- **Proveedor externo que envía avisos falsos.**
- **Cadena de suministro:** dependencias, imágenes, acciones de CI.

**Fronteras de confianza:**
1. Internet → Caddy (TLS).
2. Caddy → nginx (interfaz y `/api`).
3. nginx → backend.
4. Backend → PostgreSQL, como el rol `billing_app`, sin `BYPASSRLS`.
5. Backend → APIs externas: Mercado Pago, Bold, el proveedor de facturación y las URLs de webhooks de los clientes.

## 3. Controles implementados y probados

| Riesgo (OWASP Top 10 / API Top 10) | Control | Evidencia |
|---|---|---|
| IDOR/BOLA entre organizaciones | El tenant sale siempre de la identidad autenticada, nunca del cliente. RLS en todas las tablas con `tenant_id` (la prueba SQL falla si una no la tiene), FK compuestas, rol de app sin `BYPASSRLS` ni ownership. Un recurso de otro tenant responde igual que uno inexistente. | `tenant_isolation_test.sql`, `*ApiTest` |
| Escalada de privilegios | Permisos por rol en la capa de aplicación (`CurrentUser.require`). `Role.canGrant`: solo se asignan roles contenidos en los propios. El dueño no se degrada; nadie modifica su propio acceso. | `IdentityDomainTest`, `AccountServicesTest` |
| Contraseñas | Argon2id; mínimo 12 caracteres; el mismo error y el mismo tiempo para usuario inexistente, contraseña errada o cuenta bloqueada | `IdentityServicesTest` |
| Abuso con credenciales válidas | Límite por credencial autenticada: 300 peticiones/min por clave de API y 600 por persona, configurables; 429 con `Retry-After`. Acota el daño de una clave o un token filtrados y de una integración en bucle. | `PrincipalRateLimitFilterTest` |
| Fuerza bruta | Bloqueo progresivo por cuenta (5 fallos → 15 min, duplicando hasta 24 h). Límite por IP en login, segundo paso, registro, recuperación, invitaciones y vista pública de documentos. La IP real la fija Caddy, que descarta el `X-Forwarded-For` del cliente. | `IdentityServicesTest`, `AuthRateLimitFilterTest` |
| **Contraseña filtrada (ADR-0034)** | Verificación en dos pasos TOTP opcional:<br>• secreto cifrado con AES-256-GCM ligado al usuario;<br>• reto de 256 bits (5 min, 5 intentos), sin token ni cookie antes del código;<br>• antirreplay por paso;<br>• bloqueo progresivo por usuario que no se reinicia al acertar la contraseña;<br>• 10 códigos de recuperación de un solo uso guardados como hash;<br>• desactivar pide contraseña y código, y se avisa por correo. | `TotpTest` (vectores RFC 6238), `TwoFactorServiceTest`, `TwoFactorApiTest`, `AesGcmSecretSealerTest`, e2e `seguridad.spec.ts` |
| Sesiones | Access token JWT HS256 de 15 min, solo en memoria del navegador. Refresh opaco de 256 bits en cookie `HttpOnly; Secure; SameSite=Strict` limitada a `/api/v1/auth`, rotado en cada uso; reutilizar uno gastado revoca la familia. Refresh y logout exigen la cabecera `X-Billing-Client` (CSRF). | `IdentityServicesTest`, `AuthFlowTest` |
| Recuperar contraseña | Enlace de un solo uso (256 bits, 30 min; solo vale el último; 5 por hora). Misma respuesta exista o no la cuenta. Cierra todas las sesiones y avisa. El token va en el fragmento `#` y no llega a registros ni a `Referer`. | `AccountServicesTest`, `AccountApiTest`, e2e `cuenta.spec.ts` |
| Claves de API (ADR-0032) | `bpk_<prefijo>_<secreto 256 bits>`, guardadas como SHA-256 y comparadas en tiempo constante. Expiración y revocación. Solo permisos de operación: nunca equipo, plan, proveedor, claves, webhooks ni dos pasos (`requireUser`). | `IntegrationApiTest`, `TwoFactorApiTest` |
| SSRF por webhooks salientes (ADR-0033) | • Solo HTTPS, a un dominio público, en los puertos 443 u 8443.<br>• Antes de cada envío se resuelve el DNS y se rechazan las IP privadas, loopback, link-local (incluidos los metadatos de la nube), CGNAT, multicast y sus equivalentes IPv6.<br>• Sin redirecciones; 10 s máximo; la respuesta se descarta. | `WebhookTargetTest`, `SafeWebhookSenderTest` |
| Webhooks entrantes falsificados | **Proveedor:** HMAC con ventana de 5 min y deduplicación.<br>**Bold:** token opaco por comercio más firma; el contenido no se cree.<br>**Mercado Pago:** firma `x-signature`; el contenido no se cree, el pago se consulta a la API, y solo un pago real, aprobado, en COP, por el valor exacto y con nuestra referencia activa un plan. | `ProviderWebhookServiceTest`, `PaymentsApiTest`, `SubscriptionCheckoutServiceTest`, `SubscriptionPaymentsApiTest` |
| Webhooks salientes verificables | Firma `t=…,v1=HMAC-SHA256`. El secreto se muestra una vez y se guarda cifrado. | `WebhookServiceTest` |
| Secretos en reposo | AES-256-GCM con llave maestra fuera de la base (`BILLING_SECRETS_KEY`), nonce aleatorio y datos asociados (tenant/referencia o usuario): una copia a otra fila no se descifra. Tokens y códigos de un solo uso, solo como hash. | `AesGcmSecretSealerTest`, `PaymentsApiTest` |
| Secretos en logs | `toString` enmascarado en peticiones con contraseñas, códigos y llaves. Los correos con enlaces nunca se imprimen. | revisión de código |
| Documento compartido públicamente (ADR-0027) | Token de 256 bits en el fragmento de la URL; solo se guarda el hash; vence a los 180 días; límite de enlaces por documento y por IP. | `DocumentSharingServiceTest`, `DocumentApiTest` |
| Inyección SQL | Solo consultas parametrizadas (`JdbcClient` con parámetros nombrados), sin SQL dinámico con datos del usuario | revisión de código; CodeQL |
| XSS | React escapa todo el texto. CSP `script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'`. Correos con datos escapados. | job Docker (cabeceras), `AccountServicesTest` |
| Cabeceras e indexación (ADR-0038) | Las cabeceras de seguridad están en un solo archivo (`frontend/nginx/security-headers.conf`) incluido en cada `location`, porque nginx descarta las heredadas cuando una `location` agrega las suyas. La app y los documentos compartidos llevan `X-Robots-Tag: noindex, nofollow`; `robots.txt` excluye `/api/` y `/documento`. El dominio que se escribe en la canónica y el sitemap sale de la cabecera Host solo si tiene forma de nombre de host; en producción Caddy solo atiende el dominio configurado. | paso "SEO" del job Docker |
| Inyección en CSV exportado | Las celdas que empiezan por `= + - @` (o tabulador / CR) llevan `'` delante | `CsvTest` |
| Abuso por tamaño | Cuerpo máximo de 2 MB en Caddy y 1 MB en nginx; importación de 1 MB y 2.000 filas; paginación con límite | `CsvTest`, `ImportAndReportApiTest` |
| Integridad financiera y tributaria | `BigDecimal` con redondeo explícito; restricciones en base de datos; facturas numeradas inmutables por trigger. `ACCEPTED` exige evidencia del proveedor; un cobro de prueba nunca emite un documento real. | `DocumentCalculatorTest`, `InvoiceTest`, `tenant_isolation_test.sql` |
| Auditoría | `audit_log` sin `UPDATE`/`DELETE` para la app | `tenant_isolation_test.sql` |
| Transporte | HTTPS automático (Let's Encrypt), HSTS de 1 año, HTTP→HTTPS. `/actuator` cerrado. PostgreSQL sin puerto publicado. Contenedores sin root. Documentación de la API cerrada por defecto en producción. | job "Kit de producción" |
| Copias de seguridad | `pg_dump` diario cifrado con `age` (la llave privada no está en el servidor), copia externa y prueba de restauración automatizada | job "Kit de producción" |
| Cadena de suministro (ADR-0035) | **Análisis en cada cambio:** CodeQL `security-extended` (Java, TypeScript, workflows), gitleaks (historial completo) y OSV-Scanner (dependencias Maven transitivas y lockfile de pnpm). Bloquean la integración.<br>**Binarios de los escáneres:** verificados por SHA-256; acciones fijadas por SHA de commit.<br>**pnpm:** lockfile congelado, `minimumReleaseAge` de 7 días, scripts de instalación bloqueados; Dependabot semanal. | workflow "Seguridad" |

## 4. Resultado de los análisis automáticos (2026-11-20)

- **gitleaks:** 0 secretos en el historial y el árbol. La llave de las pruebas está marcada `gitleaks:allow`.
- **OSV-Scanner:** 0 vulnerabilidades conocidas en dependencias Maven (árbol resuelto) y pnpm, tras subir vitest a 4.1.12.
- **CodeQL `security-extended`:** 0 hallazgos en TypeScript y workflows. En Java hubo 3:
  - `java/polynomial-redos` en la validación de correo: corregido sin expresión regular, con prueba de entrada hostil.
  - `java/uncontrolled-arithmetic` en `Base32`: corregido.
  - `java/spring-disabled-csrf-protection`: **falso positivo justificado.** La API autentica con `Authorization: Bearer`, que el navegador no envía solo. Las únicas rutas con cookie (refresh y logout) usan `SameSite=Strict`, `Path=/api/v1/auth` y exigen la cabecera `X-Billing-Client`. Se puede descartar en GitHub → Security → Code scanning con este motivo.

## 5. Riesgos residuales conocidos

Ordenados por prioridad sugerida para la auditoría.

1. **DNS rebinding en webhooks salientes:** el DNS se valida y luego `HttpClient` vuelve a resolver al conectar. Un dominio malicioso con TTL 0 podría apuntar a una IP interna en la segunda resolución. Mitigación prevista: conectar a la IP ya validada, con SNI y `Host` del dominio. Los únicos servicios internos alcanzables están en la red de Docker y exigen autenticación, salvo los metadatos del proveedor del VPS.
2. **Límites de peticiones en memoria y por ventana fija** (por IP en autenticación; por clave de API y por persona en el resto):
   - con varias instancias hay que moverlos a un almacén compartido;
   - una ventana fija permite ráfagas de hasta el doble del cupo en el cambio de minuto.
3. **La verificación en dos pasos es opcional** y no se puede exigir para todo el equipo de una organización. El soporte para quien pierde el teléfono y los códigos es manual (DEPLOYMENT.md 3.2). Activarla no cierra las otras sesiones abiertas de la persona; cambiar la contraseña sí.
4. **Access token vigente tras retirar a un miembro o cambiar su rol:** sigue sirviendo hasta 15 minutos.
5. **Enumeración de correos en el registro** (`EMAIL_ALREADY_REGISTERED`): se aceptó por usabilidad; lo mitiga el límite por IP.
6. **`style-src 'unsafe-inline'`** en la CSP, por Swagger UI (solo en desarrollo; en producción está apagada). Evaluar quitarla del nginx de producción tras probar la interfaz con la CSP estricta. La interfaz no inyecta HTML.
7. **Funciones `SECURITY DEFINER`:** corren como dueño y deben revisarse en cada cambio. Están acotadas a búsquedas por hash o token y a colas.
8. **Un `app.tenant_id` mal fijado por la aplicación no lo detecta RLS.** Lo mitiga que solo `TenantTransactions` lo fija, a partir de la identidad autenticada (MULTITENANCY.md).
9. **Verificación de firmas de proveedores reales:** el esquema de firma de webhooks de Muisca y otros proveedores está por confirmar con su documentación y su contrato.
10. **Imágenes Docker:** el análisis de sus paquetes del sistema operativo es informativo, no bloqueante. Las imágenes base se actualizan con Dependabot.
11. **Pérdida de `BILLING_SECRETS_KEY`:** sin ella no se descifran las llaves de pasarela, de webhooks ni de dos pasos. Debe respaldarse fuera del servidor.
12. **Sin WAF ni protección DDoS** delante de Caddy. Es suficiente para el volumen inicial; evaluar Cloudflare (proxy) si hay abuso.
