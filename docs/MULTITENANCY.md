# Multi-tenancy

## Estrategia

Base de datos y esquema compartidos; cada tabla de datos de una organización tiene `tenant_id`. Dos barreras independientes:

1. **Aplicación**: todo puerto de repositorio exige `TenantId` (`InvoiceRepository.findById(tenant, id)`; no existe un `findById` global). El `TenantId` sale de la identidad autenticada (JWT o API key), nunca de un parámetro, cabecera o cuerpo enviado por el cliente.
2. **Base de datos**: Row-Level Security en todas las tablas con `tenant_id`. La aplicación se conecta como `billing_app`, que no es dueño de tablas ni tiene `BYPASSRLS`. Al inicio de cada transacción fija `SELECT set_config('app.tenant_id', ?, true)` (parametrizado, local a la transacción). Si no se fija, las políticas no devuelven filas (falla cerrada).

Además, las FK entre tablas de tenant son compuestas `(tenant_id, id)`: una línea, historial o job de un tenant no puede apuntar a una factura de otro, aun con un error en la aplicación.

Por qué no esquema o base por tenant: con cientos o miles de organizaciones pequeñas multiplica migraciones, conexiones y costo operativo, sin mejorar el aislamiento lógico respecto a RLS bien probado. Se reevalúa si un cliente grande exige aislamiento físico (ver ADR-0002).

## Operaciones sin tenant en contexto

Algunos flujos ocurren antes de conocer el tenant. Cada uno pasa por una función `SECURITY DEFINER` acotada, con `search_path` fijo y `EXECUTE` revocado a `PUBLIC`:

| Función | Uso | Qué devuelve |
|---|---|---|
| `auth_find_api_key(prefix)` | autenticación por API key | tenant, hash y scopes de esa clave (org activa, no revocada) |
| `auth_find_refresh_token(hash)` | renovación de sesión | datos del token por hash exacto |
| `register_organization(...)` | alta de organización | crea organización + dueño + membresía, atómico |
| `webhook_find_account(token_hash)` | webhooks de proveedor | cuenta de proveedor por hash del token de URL |
| `claim_submission_jobs(limit, lease)` | worker de envíos | jobs vencidos (con `SKIP LOCKED`); luego cada job se procesa con el tenant del job |

`memberships` y `refresh_tokens` permiten además lectura por `app.user_id` (un usuario ve sus propias membresías para elegir organización al iniciar sesión).

## Evidencia (pruebas ejecutadas)

`backend/src/test/sql/run.sh` crea una base desechable, aplica las migraciones como `billing_owner` y ejecuta `tenant_isolation_test.sql` como `billing_app`. Resultado del 2026-11-05 en PostgreSQL 16.15: **49/49 pruebas pasan**, entre ellas:

- sin tenant en contexto no se ve ninguna fila;
- el tenant A solo ve sus clientes, facturas, organización, membresías y envíos;
- IDOR/BOLA: leer, actualizar o borrar por id un recurso de B afecta 0 filas;
- insertar con `tenant_id` de B o mover una fila de A a B es rechazado (`42501`);
- A no puede darse una membresía en B (escalada);
- una línea de A no puede referenciar una factura de B (FK compuesta);
- A no puede consumir la numeración de B;
- toda tabla con `tenant_id` tiene RLS activa (prueba de completitud: falló una vez y detectó `refresh_tokens`, ya corregido).

En Java, `SubmitInvoiceServiceTest` y `ProviderWebhookServiceTest` prueban que un tenant no puede transmitir la factura de otro (mismo error que un id inexistente) y que un webhook de la cuenta de B no altera facturas de A.

## Riesgos residuales

- Si la aplicación fija un `app.tenant_id` equivocado, RLS no lo detecta: la corrección depende de que el tenant salga solo del principal autenticado. Por eso habrá un único punto (filtro/aspecto transaccional) que lo fija, cubierto por pruebas de integración.
- Con un pool de conexiones, el tenant debe ser local a la transacción (`set_config(..., true)`), nunca de sesión, para que no se filtre a la siguiente petición.
- Las funciones `SECURITY DEFINER` corren con el rol dueño (con `BYPASSRLS`); cada una debe revisarse como código de seguridad.
