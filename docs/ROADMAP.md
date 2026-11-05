# Hoja de ruta

## Bloqueos activos

| Bloqueo | Impacto | Cómo se desbloquea |
|---|---|---|
| El entorno de desarrollo no accede a Maven Central, Spring Initializr, npm ni Docker Hub (proxy 403); sin daemon de Docker | No se puede compilar la capa Spring ni el frontend, ni correr Testcontainers | Ejecutar el build en una máquina con internet, o habilitar esos registros en la red del entorno |
| Contrato con un proveedor tecnológico | No hay adaptador real | Elegir proveedor (Kiai es el candidato con API pública verificada) y obtener credenciales de pruebas |
| Precios y límites de planes | La tabla `plans` existe sin datos | Decisión comercial del propietario |

## Hecho (2026-11-05, con pruebas ejecutadas)

- Núcleo de dominio sin frameworks: motor tributario con vigencias, factura con máquina de estados, numeración, contrato de proveedores, registro/enrutamiento, adaptador simulado, envío idempotente con manejo de resultados inciertos, webhooks verificados.
- 53 pruebas Java (runner offline compatible con JUnit 5) y 49 pruebas SQL de aislamiento e invariantes contra PostgreSQL 16; prueba de concurrencia de numeración (500 asignaciones paralelas, 0 duplicados).
- Esquema completo V1–V5: identidad, organizaciones, membresías, API keys, catálogo, clientes, productos, numeración, facturas, cuentas de proveedor, secretos, outbox, webhooks, idempotencia de API, planes, suscripciones, consumo y auditoría.

## Siguiente (requiere el build con red) — orden recomendado

1. **Build**: `mvn verify` real; reemplazar el runner offline por Surefire; agregar ArchUnit; fijar parche de Spring Boot 4.1.x.
2. **Persistencia**: adaptadores JDBC/JPA de los puertos existentes + aspecto transaccional que fija `app.tenant_id` desde el principal autenticado; Testcontainers corriendo `tenant_isolation_test.sql`.
3. **Identidad**: registro de organización (`register_organization`), login, refresh rotatorio, membresías y roles, API keys, bloqueo y rate limiting.
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
