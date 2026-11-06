import { describe, expect, it } from 'vitest';
import type { Plan } from '../api/types';
import { annualSaving, upgradeOptions } from '../components/UpgradeOffer';

const plan = (code: string, family: Plan['family'], docs: number | undefined, monthly?: string, annual?: string): Plan => ({
  code,
  name: code,
  family,
  quotaDocuments: docs,
  quotaScope: family === 'TRIAL' ? 'TOTAL' : 'MONTHLY',
  monthlyPrice: monthly,
  monthlyBillingAllowed: monthly !== undefined,
  annualPrice: annual,
});

const catalog = [
  plan('TRIAL', 'TRIAL', 20),
  plan('MICRO', 'GENERAL', 15, undefined, '69000.00'),
  plan('EMPRENDEDOR', 'GENERAL', 40, '12900.00', '129000.00'),
  plan('NEGOCIO', 'GENERAL', 120, '29900.00', '299000.00'),
  plan('COMERCIO_500', 'COMMERCE', 500, '34900.00', '383900.00'),
  plan('COMERCIO_1200', 'COMMERCE', 1200, '64900.00', '713900.00'),
];

describe('oferta de mejora de plan', () => {
  it('desde la prueba ofrece todos los planes pagos', () => {
    expect(upgradeOptions(catalog, catalog[0]).map((p) => p.code)).toEqual(['MICRO', 'EMPRENDEDOR', 'NEGOCIO', 'COMERCIO_500', 'COMERCIO_1200']);
  });

  it('desde un plan pago ofrece solo los de su familia con más documentos', () => {
    expect(upgradeOptions(catalog, catalog[2]).map((p) => p.code)).toEqual(['NEGOCIO']);
    expect(upgradeOptions(catalog, catalog[4]).map((p) => p.code)).toEqual(['COMERCIO_1200']);
  });

  it('muestra los meses gratis del pago anual', () => {
    expect(annualSaving(catalog[2]!)).toBe(' · 2 meses gratis');
    expect(annualSaving(catalog[4]!)).toBe(' · 1 mes gratis');
    expect(annualSaving(catalog[1]!)).toBe('');
  });
});
