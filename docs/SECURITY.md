# Seguridad

> Estado al 2026-11-05. "Implementado" significa con código y pruebas ejecutadas; "diseñado" significa decidido y con esquema de datos, pendiente de código.

## Controles implementados y probados

| Riesgo (OWASP / API Top 10) | Control | Evidencia |
|---|---|---|
| Broken Object Level Authorization (IDOR/BOLA) | Repositorios exigen `TenantId`; RLS en todas las tablas de tenant; FK compuestas | `tenant_isolation_test.sql`, `SubmitInvoiceServiceTest` |
| Escalada de privilegios entre organizaciones | Políticas de escritura en `memberships`; rol de app sin `BYPASSRLS` ni ownership | `tenant_isolation_test.sql` |
| Webhooks falsificados / manipulados / reproducidos | HMAC-SHA256 sobre timestamp + cuerpo, comparación en tiempo constante, ventana de 5 min, deduplicación por `eventId`, cuenta resuelta por token opaco de URL (nunca por el cuerpo) | `ProviderWebhookServiceTest`, `MockElectronicInvoicingProviderTest` |
| Integridad de datos financieros | `BigDecimal` + redondeo explícito; restricciones de base de datos sobre totales y estados; facturas numeradas inmutables por trigger | `DocumentCalculatorTest`, `tenant_isolation_test.sql` |
| Falsa afirmación de validez tributaria | `ACCEPTED` exige `ValidationEvidence` (en dominio y por `CHECK` en base); validez legal solo en `PRODUCTION`; el adaptador simulado no opera en otro ambiente | `InvoiceTest`, `SubmitInvoiceServiceTest` |
| Exposición de secretos en logs | `ProviderAccount` solo lleva referencias y su `toString` las omite; secretos de proveedor cifrados por la app (tabla `provider_secrets`) | `MockElectronicInvoicingProviderTest` |
| Manipulación de auditoría | `audit_log` sin `UPDATE`/`DELETE` para la app | `tenant_isolation_test.sql` |
| Enumeración de recursos | Factura de otro tenant devuelve el mismo error que una inexistente | `SubmitInvoiceServiceTest`, `InvoiceLifecycleServiceTest` |
| Enumeración de usuarios en login | Mismo error para usuario inexistente, contraseña errada, cuenta deshabilitada o bloqueada; verificación de hash siempre ejecutada | `IdentityServicesTest` |
| Fuerza bruta por cuenta | Bloqueo progresivo: 5 fallos → 15 min, duplicando hasta 24 h | `IdentityDomainTest`, `IdentityServicesTest` |
| Robo de refresh token | Rotación en cada uso; reúso de un token gastado revoca la familia; rol releído en cada rotación | `IdentityServicesTest` |
| Claves de API filtradas o sobredimensionadas | Formato con prefijo + secreto de 256 bits, guardado como SHA-256, comparación en tiempo constante, expiración, sin permisos administrativos | `IdentityServicesTest` |
| Escalada de privilegios por asignación de roles | `Role.canGrant`: solo se asignan roles con permisos contenidos en los propios | `IdentityDomainTest` |
| Uso de numeración real en pruebas | Resolución y cuenta de proveedor deben ser del mismo ambiente; evidencia de otro ambiente se rechaza | `InvoiceTest`, `InvoiceLifecycleServiceTest` |

## Diseñado, pendiente de implementar (capa Spring)

**Autenticación de usuarios** (ADR-0003) — **implementada y probada por HTTP real** (`AuthFlowTest`, CI): registro, login, refresh rotatorio, logout, `/api/v1/auth/me`; Argon2id; JWT HS256 de 15 min con secreto ≥ 256 bits por variable de entorno (`BILLING_JWT_SECRET`); cookie `bp_refresh` HttpOnly/Secure/SameSite=Strict en `/api/v1/auth` + cabecera `X-Billing-Client` obligatoria para refresh/logout; límite de 10 intentos/min por IP en endpoints de sesión; el contador de fallos y la revocación por reúso se confirman aunque la petición falle
- Contraseñas con Argon2id (`Argon2PasswordEncoder` de Spring Security + BouncyCastle). Mínimo 12 caracteres, verificación contra listas de contraseñas comprometidas en la roadmap.
- Access token JWT de 15 min firmado por la plataforma (validado con el resource server de Spring); claims: `sub`, `tid` (tenant), `role`, `jti`.
- Refresh token opaco de 256 bits, guardado como hash SHA-256, rotado en cada uso; reúso de un token ya rotado revoca toda la familia. Viaja en cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. Al usar cookie, el endpoint de refresh exige verificación de `Origin` (CSRF).
- Access token del frontend solo en memoria (no `localStorage`).
- Bloqueo progresivo por usuario (`failed_login_count`, `locked_until`) y rate limiting por IP en login/registro.

**Integraciones (p. ej. AdoptaFácil)**: API keys por organización, formato `bpk_<prefijo>_<secreto>`; se guarda prefijo + SHA-256; scopes; expiración y revocación; uso registrado en auditoría.

**Autorización**: roles `OWNER`, `ADMIN`, `BILLER`, `VIEWER` por membresía; verificación en la capa de aplicación (no en el frontend).

**API**: validación con Jakarta Validation; errores RFC 9457 sin trazas; CORS con lista explícita de orígenes; cabeceras de seguridad; `Idempotency-Key` en POST (tabla `api_idempotency_keys`); paginación con límite máximo.

**SSRF**: las URLs de proveedores se fijan en configuración del despliegue por adaptador; nunca se aceptan URLs de proveedor desde datos de un tenant.

**Secretos**: llave maestra AES-256-GCM en variable de entorno (o gestor de secretos), con `key_version` para rotación. Nada de secretos en el repositorio (`.gitignore` excluye `.env*`).

**Dependencias**: OWASP Dependency-Check o equivalente en CI cuando exista acceso a los registros.

## Riesgos residuales conocidos

1. El límite por IP es en memoria (una instancia). Si se escala a varias instancias hay que moverlo a la base de datos o a un almacén compartido.
2. El registro informa si un correo ya existe (`EMAIL_ALREADY_REGISTERED`): facilita la enumeración de correos a cambio de usabilidad; lo mitiga el límite por IP. Revisar cuando haya verificación por correo.
2. El código Java solo se ha ejecutado con un runner offline compatible con la API de JUnit 5, no con JUnit/Surefire reales.
3. Un `app.tenant_id` mal fijado por la aplicación no lo detecta RLS (ver MULTITENANCY.md).
4. Las funciones `SECURITY DEFINER` corren como dueño con `BYPASSRLS`; deben revisarse en cada cambio.
5. El esquema de firma de webhooks de cada proveedor real está por confirmar; `HmacWebhookVerifier` solo aplica si el proveedor usa HMAC con secreto compartido.
