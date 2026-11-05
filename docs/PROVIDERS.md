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

| Adaptador | Estado | Notas |
|---|---|---|
| `mock` | Implementado y probado | Solo ambiente `SIMULATION`; claves `SIMULATED-…`; no contacta a la DIAN ni imita sus respuestas; escenarios: aceptar, pendiente, rechazar, timeout tras recepción, no disponible |
| Kiai | **Candidato, sin código** | Publica un SDK .NET en NuGet (`Kiai.Facturacion`, v1.0.0.18, actualizado 2025-04-21) que integra con la API de kiai.co, con ayuda en `apps.kiai.co/api/Help`. Falta: contrato comercial, modalidad multi-cliente, credenciales sandbox, capacidades reales |
| Plemsi | Sin verificar | No se encontró documentación oficial de su API en la búsqueda del 2026-11-05 |
| GridBilling | Sin verificar | Ídem |
| "Muisca" | **No es un proveedor** | Ver abajo |

No se crean paquetes vacíos para proveedores sin verificar: un adaptador se agrega cuando hay documentación y credenciales de prueba reales.

## MUISCA no es un proveedor tecnológico

MUISCA es el sistema informático de la DIAN. La "Facturación gratuita DIAN" es un servicio web para que un contribuyente facture por sí mismo; la página oficial de la DIAN no menciona API ni servicio web para integradores. Las vías que la DIAN describe son: solución gratuita, desarrollo propio (software propio habilitado por el facturador) y proveedor tecnológico autorizado.

Implicación: no se planea un adaptador "Muisca". Integrarse directamente al servicio web de la DIAN en nombre de muchos contribuyentes es, en la práctica, el rol de proveedor tecnológico, que exige autorización — fuera del alcance decidido (ADR-0008).

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
