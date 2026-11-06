# Planes (aprobados por el propietario el 2026-11-05)

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
| Micro (solo anual: $69.000/año) | $6.900 equivalente | 15 | Ilimitados | 83% | 91% |
| Emprendedor | $12.900 | 40 | Ilimitados | 75% | 88% |
| Negocio | $29.900 | 120 | Ilimitados | 68% | 84% |
| Pro | $69.900 | 300 | Ilimitados | 66% | 83% |
| Documento adicional (solo con aceptación expresa) | $150 c/u | — | — | 47% | 73% |

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

## Excedentes

Al llegar al límite no se cobra nada automáticamente: se ofrece mejorar el plan, y solo si el cliente acepta expresamente se emiten documentos adicionales a $150. La aceptación vale para el ciclo mensual en curso y el plan actual (ADR-0014).

## Sostenibilidad desde el inicio

Costos fijos conocidos: prepago del proveedor $399.900/año (~$33.300/mes). Cada cliente Micro aporta ~$4.550/mes después del costo de sus documentos ($5.750 − 15 × $80), así que **unos 8 clientes Micro cubren el compromiso con el proveedor**. Servidor, dominio, correo y comisión de Mercado Pago se suman cuando se contraten (no se inventan aquí): punto de equilibrio = costos fijos mensuales ÷ aporte mensual promedio por cliente. El margen mejora solo con el volumen, porque el costo por documento baja de ~$80 a ~$40 sin cambiar precios.

## Prueba gratis

**20 documentos reales en producción**, sin tarjeta y sin límite de tiempo. Simulación ilimitada; el set de pruebas de habilitación DIAN no cuenta. Costo máximo por prueba: ~$1.800.

## Comercio y restaurantes (POS de alto volumen) — análisis del 2026-11-06

**La cuota es mensual, no diaria.** Los planes actuales sirven para quien emite pocas decenas o cientos de documentos al mes. Una tienda obligada a facturar emite un documento por venta: con 100 ventas al día son unos 3.000 al mes.

**Costo con el proveedor recomendado (Muisca):**
- Paquetes por adelantado, con 12 meses de vigencia: $80, $45, $40 y $30 por documento. El de $30 es Enterprise: 120.000 documentos por $3.590.000 ([precios](https://muisca.io/precios/), consultado el 2026-11-06).
- 3.000 documentos al mes cuestan entre $90.000 y $240.000 solo en proveedor.

**Competencia:** Siigo vende POS con facturas desde POS ilimitadas desde $43.943 al mes con pago anual ([Siigo POS](https://www.siigo.com/sistema-pos/), consultado el 2026-11-06). Siigo es su propio proveedor tecnológico, así que su costo marginal por documento es casi nulo.

**Obligación:** según fuentes secundarias, la persona natural no responsable de IVA no está obligada a facturar electrónicamente; las personas jurídicas sí lo están siempre ([La República](https://www.larepublica.co/especiales/facturacion-electronica-2024/todas-las-tiendas-de-barrio-estan-obligadas-a-cumplir-con-la-facturacion-electronica-3941175)). Muchas tiendas de barrio no necesitan el producto. **Debe confirmarlo un contador con la norma vigente.**

**Recomendación (pendiente de decisión del propietario):**
1. Lanzar con el segmento donde los planes son competitivos: fundaciones, profesionales, servicios y facturación entre empresas.
2. Plan de comercio por volumen solo con costo ≤ $30 por documento. Ejemplos: Comercio 1.000 a $59.900 (~50 % de margen bruto) y Comercio 3.000 a $129.900 (~31 %). Aun así no compite en precio con un POS ilimitado; compite en facilidad e integración.
3. Documentos ilimitados reales solo con un proveedor de tarifa fija por empresa o siendo proveedor tecnológico autorizado. Los requisitos de la DIAN para lo segundo están por investigar.
