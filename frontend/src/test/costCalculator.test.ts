import { describe, expect, it } from 'vitest';
import type { Plan } from '../api/types';
import { planCosts, recommended } from '../landing/CostCalculator';

const plan = (code: string, family: Plan['family'], docs: number, monthly: string | undefined, annual: string): Plan => ({
  code, name: code, family, quotaDocuments: docs, quotaScope: 'MONTHLY', monthlyPrice: monthly, monthlyBillingAllowed: monthly !== undefined, annualPrice: annual,
});

const catalog: Plan[] = [
  plan('MICRO', 'GENERAL', 15, undefined, '69000.00'),
  plan('EMPRENDEDOR', 'GENERAL', 40, '12900.00', '129000.00'),
  plan('NEGOCIO', 'GENERAL', 120, '29900.00', '299000.00'),
  plan('COMERCIO_500', 'COMMERCE', 500, '34900.00', '383900.00'),
  plan('COMERCIO_1200', 'COMMERCE', 1200, '64900.00', '713900.00'),
];

describe('calculadora de la página pública', () => {
  it('recomienda el plan más barato que alcanza para el volumen', () => {
    expect(recommended(planCosts(catalog, 'GENERAL', 30, 'MONTHLY'))?.plan.code).toBe('EMPRENDEDOR');
    expect(recommended(planCosts(catalog, 'COMMERCE', 800, 'MONTHLY'))?.plan.code).toBe('COMERCIO_1200');
  });

  it('con pocos documentos, el anual de Micro gana frente al mensual de Emprendedor', () => {
    const best = recommended(planCosts(catalog, 'GENERAL', 10, 'MONTHLY'));
    expect(best?.plan.code).toBe('MICRO');
    expect(best?.monthly).toBe(5750); // 69.000 / 12: Micro solo se vende anual
  });

  it('el pago anual se expresa por mes', () => {
    const costs = planCosts(catalog, 'COMMERCE', 400, 'ANNUAL');
    expect(costs.find((c) => c.plan.code === 'COMERCIO_500')?.monthly).toBeCloseTo(31991.67, 1);
  });

  it('sin plan que alcance, no inventa uno', () => {
    expect(recommended(planCosts(catalog, 'GENERAL', 3000, 'MONTHLY'))).toBeUndefined();
  });
});
