# Propuesta de planes (borrador para decisión del propietario)

> 2026-11-05. Basada en el costo público de Muisca (proveedor recomendado, ver PROVIDERS.md) y en precios públicos de Siigo de noviembre de 2026. Precios en COP antes de IVA (el tratamiento de IVA de la suscripción debe confirmarlo un contador).

## Supuestos de costo por documento

- **Arranque** (prepago Pro de Muisca, $399.900 / 5.000 docs): ~$80.
- **Escala** (prepago Executive, $1.599.900 / 40.000 docs): ~$40.
- No incluye: servidor, correo, comisión de Mercado Pago, soporte. Son costos fijos o porcentuales que se cubren con el margen bruto.
- Los documentos prepagados vencen a los 12 meses y no se acumulan: el compromiso inicial con el proveedor es de $399.900/año.

## Planes propuestos

| Plan | Precio/mes | Documentos/mes | Usuarios | Margen bruto arranque | Margen bruto escala |
|---|---|---|---|---|---|
| Emprendedor | $12.900 | 40 | 1 | 75% | 88% |
| Negocio | $29.900 | 120 | 3 | 68% | 84% |
| Pro | $69.900 | 300 | 10 | 66% | 83% |
| Documento adicional | $150 c/u | — | — | 47% | 73% |

Todos los planes incluyen todos los tipos de documento (factura, notas, POS electrónico y documento soporte, cuando el proveedor los cubra), API e integraciones. Pago anual = 10 meses (2 gratis).

**Contra el mercado (Siigo, noviembre de 2026):** su plan de 120 documentos **al año** cuesta $18.744/mes; nuestro Emprendedor da 40 **al mes** (480/año) por $12.900. Su plan de 1.500 documentos/año cuesta $76.931/mes; nuestro Negocio da 1.440/año por $29.900.

## Prueba gratis

**20 documentos reales en producción**, sin tarjeta y sin límite de tiempo. El ambiente de simulación es ilimitado, y los documentos del set de pruebas de habilitación DIAN no cuentan. Costo máximo por prueba: ~$1.800.

## Riesgo a vigilar: clientes POS de alto volumen

Una tienda o restaurante puede emitir 1.000–2.000 tiquetes POS al mes. A $80/documento eso cuesta más que cualquiera de los planes. Para ellos se propone un plan POS por volumen que solo se habilita cuando el costo por documento sea ≤ $40 (escala) o con un proveedor de software propio para ese cliente (ver PROVIDERS.md).
