import { toLineInputs } from '../pages/InvoiceEditor';

const base = { key: 1, productId: '', unitCode: '94', discountKind: '' as const, discountValue: '', taxRateCodes: ['IVA_19'] };

describe('líneas de factura antes de enviarlas', () => {
  it('normaliza montos escritos a la colombiana', () => {
    const { lines, problem } = toLineInputs([{ ...base, description: 'Consulta', quantity: '2', unitPrice: '50.000' }]);
    expect(problem).toBeNull();
    expect(lines[0]).toMatchObject({ quantity: '2', unitPrice: '50000', taxRateCodes: ['IVA_19'] });
    expect(lines[0]!.discount).toBeUndefined();
  });

  it('incluye el descuento solo si se eligió', () => {
    const { lines } = toLineInputs([{ ...base, description: 'X', quantity: '1', unitPrice: '100', discountKind: 'PERCENT', discountValue: '10' }]);
    expect(lines[0]!.discount).toEqual({ kind: 'PERCENT', value: '10' });
  });

  it('explica qué línea está mal', () => {
    expect(toLineInputs([{ ...base, description: '', quantity: '1', unitPrice: '1' }]).problem).toMatch(/Línea 1/);
    expect(toLineInputs([{ ...base, description: 'A', quantity: '0', unitPrice: '1' }]).problem).toMatch(/cantidad/);
    expect(toLineInputs([{ ...base, description: 'A', quantity: '1', unitPrice: '' }]).problem).toMatch(/precio/);
  });
});
