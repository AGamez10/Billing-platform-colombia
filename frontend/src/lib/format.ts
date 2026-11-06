import type { Environment, IdentificationType, InvoiceStatus } from '../api/types';

const cop = new Intl.NumberFormat('es-CO', {
  style: 'currency',
  currency: 'COP',
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

/** $ 107.100 / $ 40.000,5 — formato colombiano; acepta número o cadena decimal. */
export function formatCop(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? cop.format(n) : '—';
}

const dateFmt = new Intl.DateTimeFormat('es-CO', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const dateTimeFmt = new Intl.DateTimeFormat('es-CO', {
  day: '2-digit',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'America/Bogota',
});

/** Fecha de calendario (yyyy-mm-dd) sin conversiones de zona: el 5 de noviembre es el 5 en cualquier navegador. */
export function formatDate(isoDate: string | undefined): string {
  if (!isoDate) return '—';
  const d = new Date(`${isoDate.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? isoDate : dateFmt.format(d);
}

/** Instante mostrado en hora de Colombia. */
export function formatDateTime(instant: string | undefined): string {
  if (!instant) return '—';
  const d = new Date(instant);
  return Number.isNaN(d.getTime()) ? instant : dateTimeFmt.format(d);
}

export const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Borrador',
  PENDING_SUBMISSION: 'Por enviar',
  SUBMITTED: 'Enviada, esperando respuesta',
  ACCEPTED: 'Aceptada',
  REJECTED: 'Rechazada',
  FAILED: 'Falló el envío',
};

export const STATUS_TONE: Record<InvoiceStatus, 'neutral' | 'progress' | 'ok' | 'bad'> = {
  DRAFT: 'neutral',
  PENDING_SUBMISSION: 'progress',
  SUBMITTED: 'progress',
  ACCEPTED: 'ok',
  REJECTED: 'bad',
  FAILED: 'bad',
};

export const ENVIRONMENT_LABEL: Record<Environment, string> = {
  SIMULATION: 'Simulación',
  HABILITATION: 'Habilitación DIAN',
  PRODUCTION: 'Producción',
};

export const ID_TYPE_LABEL: Record<IdentificationType, string> = {
  NIT: 'NIT',
  CEDULA_CIUDADANIA: 'Cédula de ciudadanía',
  CEDULA_EXTRANJERIA: 'Cédula de extranjería',
  PASAPORTE: 'Pasaporte',
  TARJETA_IDENTIDAD: 'Tarjeta de identidad',
};

export function formatIdentification(type: IdentificationType, number: string, checkDigit?: number | null): string {
  if (type === 'NIT') return `NIT ${number}${checkDigit === undefined || checkDigit === null ? '' : `-${checkDigit}`}`;
  return `${ID_TYPE_LABEL[type]} ${number}`;
}

export function isTerminal(status: InvoiceStatus): boolean {
  return status === 'ACCEPTED' || status === 'REJECTED' || status === 'FAILED';
}

/**
 * Convierte lo que escribe una persona en Colombia a un decimal canónico para la API
 * ("12.500,50" → "12500.50"). Con coma, los puntos son de miles. Sin coma, un único punto
 * seguido de 1 o 2 dígitos al final es decimal ("40000.5"); cualquier otro punto es de miles
 * ("1.500.000"). Devuelve null si no es un número.
 */
export function parseDecimalInput(raw: string): string | null {
  const s = raw.replace(/\s|\$/g, '');
  if (s === '') return null;
  let normalized: string;
  if (s.includes(',')) {
    normalized = s.replace(/\./g, '').replace(',', '.');
  } else if (/^\d+\.\d{1,2}$/.test(s)) {
    normalized = s;
  } else {
    normalized = s.replace(/\./g, '');
  }
  return /^\d+(\.\d+)?$/.test(normalized) ? normalized : null;
}
