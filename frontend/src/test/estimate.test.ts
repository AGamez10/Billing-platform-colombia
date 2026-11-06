import { estimateTotalCents } from '../lib/estimate';

describe('total estimado de la venta rápida (misma regla de redondeo del servidor)', () => {
  it('2 × $2.500 con IVA 19 % = $5.950', () => {
    expect(estimateTotalCents([{ quantity: '2', unitPrice: '2500', taxPercents: ['19'] }])).toBe(595000n);
  });

  it('redondea el impuesto por línea a 2 decimales, mitad hacia arriba', () => {
    // 3 × 333,33 = 999,99; IVA 19 % = 189,9981 → 190,00; total 1.189,99
    expect(estimateTotalCents([{ quantity: '3', unitPrice: '333.33', taxPercents: ['19'] }])).toBe(118999n);
  });

  it('suma líneas excluidas e impoconsumo sin errores de punto flotante', () => {
    expect(
      estimateTotalCents([
        { quantity: '1', unitPrice: '40000.5', taxPercents: ['0'] },
        { quantity: '1.5', unitPrice: '10000', taxPercents: ['8'] },
      ]),
    ).toBe(4000050n + 1620000n);
  });
});
