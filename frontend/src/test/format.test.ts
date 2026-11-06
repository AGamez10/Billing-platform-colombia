import { formatCop, formatDate, parseDecimalInput } from '../lib/format';

describe('lectura de montos escritos en Colombia', () => {
  it.each([
    ['50000', '50000'],
    ['50.000', '50000'],
    ['1.500.000', '1500000'],
    ['12.500,50', '12500.50'],
    ['12500,5', '12500.5'],
    ['40000.50', '40000.50'],
    ['40000.5', '40000.5'],
    ['$ 2.000', '2000'],
  ])('%s → %s', (input, expected) => {
    expect(parseDecimalInput(input)).toBe(expected);
  });

  it('rechaza lo que no es número', () => {
    expect(parseDecimalInput('')).toBeNull();
    expect(parseDecimalInput('abc')).toBeNull();
    expect(parseDecimalInput('-5')).toBeNull();
    expect(parseDecimalInput('1,2,3')).toBeNull();
  });
});

describe('formato', () => {
  it('pesos colombianos sin decimales innecesarios', () => {
    expect(formatCop(107100)).toMatch(/107\.100/);
    expect(formatCop('40000.5')).toMatch(/40\.000,5/);
    expect(formatCop(null)).toBe('—');
  });

  it('una fecha de calendario no se corre de día por la zona horaria', () => {
    expect(formatDate('2026-11-05')).toMatch(/05/);
  });
});
