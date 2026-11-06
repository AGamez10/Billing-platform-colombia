/**
 * Total estimado de una venta rápida, para mostrar "Cobrar $X" antes de emitir.
 * Usa la misma regla del servidor (redondeo a 2 decimales, mitad hacia arriba, por línea y
 * por impuesto) con aritmética entera en centavos para no arrastrar errores de punto flotante.
 * El total que vale es siempre el que devuelve el servidor al emitir.
 */
export interface EstimateLine {
  quantity: string; // decimal canónico ("2", "1.5")
  unitPrice: string; // decimal canónico ("2500", "1999.90")
  taxPercents: string[]; // porcentajes de las tarifas de la línea ("19", "8", "0")
}

const SCALE = 1_000_000n; // 6 decimales para cantidades y precios

function toMicros(decimal: string): bigint {
  const [int = '0', frac = ''] = decimal.split('.');
  return BigInt(int) * SCALE + BigInt((frac + '000000').slice(0, 6));
}

/** Divide redondeando mitad hacia arriba (valores no negativos). */
function divRoundHalfUp(n: bigint, d: bigint): bigint {
  return (n * 2n + d) / (2n * d);
}

export function estimateTotalCents(lines: EstimateLine[]): bigint {
  let total = 0n;
  for (const l of lines) {
    // base en centavos: cantidad × precio, redondeado a 2 decimales
    const grossMicro2 = toMicros(l.quantity) * toMicros(l.unitPrice); // escala 10^12
    const baseCents = divRoundHalfUp(grossMicro2, 10_000_000_000n); // a escala 10^2
    let taxCents = 0n;
    for (const p of l.taxPercents) {
      taxCents += divRoundHalfUp(baseCents * toMicros(p), 100n * SCALE);
    }
    total += baseCents + taxCents;
  }
  return total;
}

export function centsToNumber(cents: bigint): number {
  return Number(cents) / 100;
}
