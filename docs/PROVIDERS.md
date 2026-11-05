# Proveedores tecnológicos

## Contrato común

`electronicinvoicing.port.ElectronicInvoicingProvider` (ver ADR-0004 en [DECISIONS.md](DECISIONS.md)):

```java
ProviderId id();
ProviderCapabilities capabilities();
SubmissionResult submit(ProviderAccount account, ElectronicDocument document, IdempotencyKey key);
SubmissionResult queryStatus(ProviderAccount account, ExternalReference reference);
Optional<SubmissionResult> findByDocumentNumber(ProviderAccount account, String fullDocumentNumber); // opcional
ProviderEvent parseWebhook(ProviderAccount account, InboundWebhook webhook);  // verifica y traduce
```

- El adaptador recibe un `ElectronicDocument` **neutral** y lo traduce al formato del proveedor. Ninguna clase, SDK ni respuesta del proveedor sale del paquete del adaptador.
- Todos los resultados se normalizan a `SubmissionResult` (`Accepted` con evidencia, `Pending`, `Rejected`, `TransientFailure` con indicador de posible recepción, `PermanentFailure`).
- Las diferencias entre proveedores se modelan en `ProviderCapabilities` (tipos de documento, ambientes, idempotencia, consulta de estado, búsqueda por número, webhooks). `ProviderRegistry` rechaza operaciones no soportadas en vez de simularlas.
- Selección por configuración: cada tenant tiene una `provider_accounts` activa (índice único parcial). Cambiar de proveedor = activar otra cuenta; las facturas en curso terminan en la cuenta con la que se enviaron.

## Adaptadores

| Adaptador | Estado |
|---|---|
| `mock` | Implementado y probado. Solo ambiente `SIMULATION`; claves `SIMULATED-…`; no contacta a la DIAN ni imita sus respuestas |
| `muisca` (muisca.io) | **Recomendado como primer adaptador real** (ver comparación). Sin código hasta tener credenciales de sandbox |

No se crean paquetes vacíos para proveedores sin verificar: un adaptador se agrega cuando hay documentación y credenciales de prueba reales.

## Comparación con información pública (verificada 2026-11-05)

> Solo datos publicados en los sitios de cada proveedor. "No publicado" significa que no se encontró, no que no exista. Precios en COP.

| Proveedor | Modelo DIAN | Documentos publicados | Multiempresa | Precio público | Firma / certificado | Sandbox | Webhooks |
|---|---|---|---|---|---|---|---|
| **Muisca** (muisca.io, Aliaddo SAS) | Proveedor tecnológico autorizado; ISO/IEC 27001:2022 | Factura, notas crédito/débito, **documento soporte** y su nota de ajuste, **documento equivalente electrónico / tiquete POS** y sus notas, más factura de salud, cambio de divisas y tiquetes de transporte, cine, peajes (confirmado en doc.muisca.io el 2026-11-05). Nómina y RADIAN no aparecen | Sí (subcuentas/multiempresa en todos los planes) | Prepago anual: Pro $399.900 / 5.000 docs (~$80); Business Plus $674.900 / 15.000 (~$45); Executive $1.599.900 / 40.000 (~$40); Enterprise $3.590.000 / 120.000 (~$30). Por demanda: $90 → $30 por tramos, mínimo 1.000 docs/mes. Sin IVA; no acumulables | Firma electrónica **incluida** | Ambientes de pruebas/habilitación mencionados en su documentación; URL no publicada en la portada | Sí, en todos los prepago (según su página de precios) |
| **MATIAS API** | **Software propio** (cada cliente se habilita como facturador con software propio) | Factura, notas, POS electrónico, documento soporte, RADIAN; nómina con estado contradictorio | Sí (bolsa de documentos repartible entre NITs; mínimo 3 clientes para precio distribuidor) | $220.000 / 5.000 docs/año ($44) … $10.000.000 / 1.000.000 ($10) | **Certificado aparte: $104.000 por NIT/año** ($99.000 desde 100 NITs) | Sí, aislado | No confirmado |
| **Alegra e-provider** | Proveedor tecnológico autorizado (Soluciones Alegra S.A.S.) | Factura, notas, POS electrónico, documento soporte, nómina | Habilitación por compañía vía API (set de pruebas automatizable) | No publicado | No publicado | Sí | Sí (incluye `general.governmentStatusChanged`) |
| Bilidox | Proveedor tecnológico autorizado | Todos, incluidos POS y documento soporte | No publicado | Solo planes para empresa final (desde $24.000/año por 15 docs) | No publicado | Sí | No publicado |
| Data Invoice Colombia | Software propio | Todos | No publicado | $90.000/año con 3.000 docs y certificado; luego $30 → $20 | 1 certificado incluido | No publicado | No |
| Factus | Se vincula como proveedor tecnológico en la DIAN | Factura, notas, documento soporte, nómina, salud; POS no | "Aliados e integradores", sin detalle | No publicado | No publicado | Sí | No |
| Kiai, Plemsi, GridBilling | — | No se encontró API ni precios públicos | — | — | — | — | — |

### Corrección (2026-11-05)

La versión anterior de este documento decía que "Muisca no es un proveedor". Era una confusión: MUISCA es el sistema de la DIAN, pero **muisca.io** es una API comercial de Aliaddo SAS, proveedor tecnológico autorizado. Lo que sigue siendo cierto: la "Facturación gratuita DIAN" no tiene API para integradores.

### Muisca: lo que dice su documentación técnica (doc.muisca.io, leída el 2026-11-05)

La documentación también se refiere a la API como "Aliaddo Nitro"; Muisca es su marca comercial.

| Tema | Lo documentado | Consecuencia en nuestro adaptador |
|---|---|---|
| URL base | `https://api.muisca.io/v2`, la misma para todos los ambientes | Una sola URL configurada por despliegue (protección SSRF: nunca viene del tenant) |
| Ambiente | Campo `mode` en el cuerpo: `Test` (validación interna sin DIAN), `Habilitation` (set de pruebas DIAN, requiere `testSetId` GUID), `Production` | `HABILITATION` → `Habilitation`, `PRODUCTION` → `Production`. `Test` es un sandbox del proveedor sin DIAN: se usará para pruebas de contrato del adaptador, nunca como evidencia de validación |
| Autenticación | `Authorization: Bearer <token>`; el token se genera en el portal **por organización** | Un token por tenant, guardado cifrado en `provider_secrets`: coincide con el diseño de `ProviderAccount` |
| Alta de empresas | Existe el esquema `AddCompany`, pero no se pudo leer si hay endpoint o si es solo por portal | **Por confirmar**: si es solo por portal, dar de alta a cada cliente es un paso manual en Muisca |
| Endpoints | Factura `POST /documents/invoices`; POS y documentos equivalentes `POST /documents/equivalent-documents` (`code` "20" para POS); documento soporte `POST /documents/bills` (con `supplier` en vez de `customer`); notas crédito/débito y de ajuste con rutas propias | Un mapeo por `DocumentKind` |
| Respuesta | Sincrónica en el ejemplo: `Id`, `Cufe`, `Sequence`, `Qr`, `Status` ("Aprobada"), `StatusReasons` | `Aprobada` + `Cufe` → `Accepted` con evidencia; rechazo → `Rejected` con `StatusReasons` |
| Consulta | `GET /documents/{tipo}/{id}` y `GET /documents/{tipo}/find-by-prefix-and-consecutive` | `statusQuery = true`, `lookupByDocumentNumber = true` |
| DIAN no responde | Muisca reintenta solo (4 intentos inmediatos y 6 cada 30 min, hasta 3 h) y marca `DianNoDisponible` (contingencia 04). **No se debe volver a crear el documento** | `DianNoDisponible` → `Pending`; el worker consulta por id, nunca reenvía |
| Rechazo | Corregir la causa y reenviar; no cambiar prefijo/consecutivo para esquivar el rechazo | Requiere el flujo de corrección de rechazados (ROADMAP) |
| Idempotencia | No documentada | `idempotentSubmission = false`: ante resultado incierto, primero se busca por prefijo y consecutivo (ya implementado en `SubmitInvoiceService`) |
| Webhooks | No aparecen en la documentación técnica, aunque la página de precios los incluye | `webhooks = false` hasta confirmarlo; el worker consulta el estado |
| Límites | Responde `429`; los planes limitan a 20–300 peticiones/min | El worker respeta `429` con espera exponencial |
| Datos de las partes | Cliente con tipo de persona, régimen, responsabilidades fiscales, dirección con códigos DANE; sucursal; resolución completa | Hay que ampliar `Party` y el perfil del emisor antes del adaptador real |

### Análisis económico por cliente (costo directo del proveedor)

| Cliente tipo | Docs/año | Muisca (~$80 arranque / ~$40 escala) | MATIAS ($44 arranque / $21 escala) + certificado $104.000 |
|---|---|---|---|
| Micro (20/mes) | 240 | $19.200 / $9.600 | $114.560 / $109.040 |
| Pequeño (120/mes) | 1.440 | $115.200 / $57.600 | $167.360 / $134.240 |
| POS intensivo (1.500/mes) | 18.000 | $1.440.000 / $720.000 | $896.000 / $482.000 |

Conclusión: el modelo de proveedor tecnológico con firma incluida (Muisca) es mucho más barato para el segmento objetivo (micro y pequeños), porque el certificado por NIT de un modelo de software propio cuesta más que todo su consumo. Para clientes con muy alto volumen (POS de restaurantes y tiendas con cientos de tiquetes diarios) el modelo de software propio (MATIAS) se vuelve más barato. La arquitectura ya permite un proveedor distinto por tenant.

### Recomendación

1. **Primario: Muisca (muisca.io).** Autorizado, firma incluida, multiempresa y webhooks en todos los planes, precio público y entrada baja ($399.900/año). Su documentación confirma factura, notas, POS electrónico y documento soporte. Autenticación Bearer token; consulta por id (`GET /v2/documents/{documentType}/{id}`) y **por prefijo y consecutivo** (`GET /v2/documents/{documentType}/find-by-prefix-and-consecutive`), lo que en nuestro contrato es `lookupByDocumentNumber = true`: permite reintentar envíos inciertos sin duplicar. Idempotencia por clave y esquema de firma de webhooks: no documentados en la portada, por verificar en el sandbox.
2. **Si Muisca no cubre POS/documento soporte, o para clientes POS de alto volumen: MATIAS API** (cubre todo, sandbox público, costo por documento muy bajo), asumiendo el certificado por NIT.
3. **Alegra e-provider** cubre todo y está bien documentado, pero Alegra es competidor directo: depender de su infraestructura le da visibilidad sobre nuestros volúmenes y poder sobre nuestro costo. Solo como respaldo.

## Lista de verificación antes de usar un proveedor en producción

1. Contrato que permita expresamente operar como plataforma SaaS para múltiples emisores (credenciales por organización o esquema de sub-cuentas).
2. Autorización vigente del proveedor ante la DIAN (consultar el listado oficial de proveedores autorizados).
3. Documentación de API: autenticación, emisión, notas crédito/débito, consulta, búsqueda por número, idempotencia, webhooks y su firma, límites de tasa, códigos de error.
4. Ambiente de pruebas/habilitación separado de producción, con credenciales distintas.
5. Tarifas por documento por escrito (alimentan `usage_events.provider_unit_cost`; no se inventan).
6. Prueba del adaptador contra el sandbox con la suite de contrato compartida (ver ROADMAP).

## Normativa de referencia (verificado 2026-11-05)

- Anexo Técnico de Factura Electrónica de Venta **versión 1.9**, adoptado por la Resolución DIAN 000165 de 2023 (art. 60); su obligatoriedad se aplazó al 1 de mayo de 2024 por la Resolución 000008 de 2024.
- Re-verificar antes del primer adaptador real si hubo una versión posterior del Anexo.
