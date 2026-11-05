# Propuesta de planes (borrador para decisión del propietario)

> Actualizado 2026-11-05. Basado en el costo público de Muisca (proveedor recomendado, ver PROVIDERS.md) y en precios públicos de competidores consultados ese día. Precios en COP antes de IVA (el tratamiento de IVA de la suscripción debe confirmarlo un contador).

## ¿Por qué nos pagarían a nosotros y no al proveedor?

1. **El proveedor no le vende la API a una tienda.** Muisca vende acceso por API a empresas de software: exige desarrollo propio y un prepago mínimo de $399.900/año por 5.000 documentos. Un comercio o una fundación no puede usarlo sin un software encima.
2. **El software minorista del mismo grupo cuesta el doble que nosotros.** Aliaddo (operador de Muisca) cobra $59.900/mes por 100 documentos. Nuestro plan Negocio: $29.900 por 120.
3. **Costo por documento al por mayor.** Compramos documentos a ~$80 (y ~$40 a escala) y los revendemos con margen, más barato que el precio minorista de cualquier competidor.
4. **Usuarios ilimitados en todos los planes.** No nos cuestan nada y Siigo cobra por usuario.

**Competencia real en precio bajo:** la solución gratuita de la DIAN y los planes gratuitos (Dataico, Siigo con 5 documentos/año). A precio cero no se compite; se compite con **menos pasos, menos errores, POS y documento soporte en el mismo lugar, API para integraciones (AdoptaFácil) y habilitación asistida**.

## Supuestos de costo por documento

- **Arranque** (prepago Pro de Muisca, $399.900 / 5.000 docs): ~$80.
- **Escala** (prepago Executive, $1.599.900 / 40.000 docs): ~$40.
- No incluye: servidor, correo, comisión de Mercado Pago, soporte.
- Los documentos prepagados vencen a los 12 meses y no se acumulan.

## Planes propuestos

| Plan | Precio/mes | Documentos/mes | Usuarios | Margen bruto arranque | Margen bruto escala |
|---|---|---|---|---|---|
| Micro | $6.900 | 15 | Ilimitados | 83% | 91% |
| Emprendedor | $12.900 | 40 | Ilimitados | 75% | 88% |
| Negocio | $29.900 | 120 | Ilimitados | 68% | 84% |
| Pro | $69.900 | 300 | Ilimitados | 66% | 83% |
| Documento adicional | $150 c/u | — | — | 47% | 73% |

Todos los planes incluyen todos los tipos de documento que cubra el proveedor (factura, notas, POS electrónico, documento soporte), API e integraciones. Pago anual = 10 meses.

## Comparación (precios públicos, 2026-11-05)

| Opción | Precio | Documentos |
|---|---|---|
| **Nosotros — Micro** | **$6.900/mes** | **15/mes (180/año)** |
| Siigo Facturación 24 | $9.992/mes (pago anual) | 24/año |
| Siigo Facturación 120 | $18.744/mes (pago anual) | 120/año |
| **Nosotros — Negocio** | **$29.900/mes** | **120/mes (1.440/año)** |
| Dataico Emprendedor | $36.000/mes | No publicado en texto |
| Aliaddo Initial | $59.900/mes | 100/mes |
| Siigo Facturación 1500 | $76.931/mes (pago anual) | 1.500/año |
| Bilidox Básico 100 | $60.000/año | 100/año |

## Prueba gratis

**20 documentos reales en producción**, sin tarjeta y sin límite de tiempo. Simulación ilimitada; el set de pruebas de habilitación DIAN no cuenta. Costo máximo por prueba: ~$1.800.

## Riesgo a vigilar: clientes POS de alto volumen

Una tienda o restaurante puede emitir 1.000–2.000 tiquetes POS al mes. A $80/documento eso cuesta más que cualquier plan. Plan POS por volumen solo cuando el costo por documento sea ≤ $40, o con un proveedor de software propio para ese cliente (ver PROVIDERS.md).
