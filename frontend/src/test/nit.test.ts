import { cleanIdNumber, nitCheckDigit } from '../lib/nit';

describe('dígito de verificación del NIT', () => {
  it('coincide con el NIT de la DIAN (800.197.268-4)', () => {
    expect(nitCheckDigit('800197268')).toBe(4);
  });

  it('coincide con el backend en otros casos', () => {
    expect(nitCheckDigit('900123456')).toBe(8);
    expect(nitCheckDigit('811011779')).toBe(8);
  });

  it('acepta el NIT con puntos como se copia del RUT', () => {
    expect(nitCheckDigit('800.197.268')).toBe(4);
    expect(cleanIdNumber(' 800.197.268 ')).toBe('800197268');
  });

  it('no calcula con letras o vacío', () => {
    expect(nitCheckDigit('')).toBeNull();
    expect(nitCheckDigit('80019726A')).toBeNull();
  });
});
