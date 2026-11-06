/**
 * Dígito de verificación del NIT (algoritmo de módulo 11 de la DIAN con los pesos primos
 * 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71). Es la misma regla que valida el
 * backend (`NitCheckDigit`); aquí solo sirve para ayudar a quien escribe. El servidor decide.
 */
const WEIGHTS = [3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71];

export function nitCheckDigit(nit: string): number | null {
  const digits = nit.replace(/[\s.]/g, '');
  if (!/^\d{1,15}$/.test(digits)) return null;
  let sum = 0;
  for (let i = 0; i < digits.length; i++) {
    const d = Number(digits[digits.length - 1 - i]);
    sum += d * (WEIGHTS[i] ?? 0);
  }
  const r = sum % 11;
  return r > 1 ? 11 - r : r;
}

/** Quita puntos y espacios que la gente copia del RUT ("900.123.456"). */
export function cleanIdNumber(value: string): string {
  return value.replace(/[\s.]/g, '');
}
