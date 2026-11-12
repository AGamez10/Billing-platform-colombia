import { http } from '../api/client';
import type { FiscalProfile, IdentificationType, Municipality, PersonType, TaxResponsibility, VatRegime } from '../api/types';

export const PERSON_TYPE_LABEL: Record<PersonType, string> = {
  LEGAL: 'Persona jurídica',
  NATURAL: 'Persona natural',
};

export const VAT_REGIME_LABEL: Record<VatRegime, string> = {
  RESPONSIBLE: 'Responsable de IVA',
  NOT_RESPONSIBLE: 'No responsable de IVA',
};

/** Responsabilidades del RUT admitidas en la factura electrónica (anexo DIAN, verificado 2026-11-12). */
export const RESPONSIBILITIES: { code: TaxResponsibility; label: string }[] = [
  { code: 'O-13', label: 'Gran contribuyente' },
  { code: 'O-15', label: 'Autorretenedor' },
  { code: 'O-23', label: 'Agente de retención en el IVA' },
  { code: 'O-47', label: 'Régimen simple de tributación' },
  { code: 'R-99-PN', label: 'Ninguna de las anteriores (No aplica – Otros)' },
];

/** Punto de partida razonable: lo más común es persona natural sin responsabilidades especiales. */
export function defaultFiscal(idType: IdentificationType): FiscalProfile {
  return {
    personType: idType === 'NIT' ? 'LEGAL' : 'NATURAL',
    vatRegime: 'NOT_RESPONSIBLE',
    responsibilities: ['R-99-PN'],
  };
}

/** R-99-PN excluye a las demás; quitar la última deja R-99-PN. */
export function toggleResponsibility(current: TaxResponsibility[], code: TaxResponsibility): TaxResponsibility[] {
  if (code === 'R-99-PN') return ['R-99-PN'];
  const others = current.filter((c) => c !== 'R-99-PN');
  const next = others.includes(code) ? others.filter((c) => c !== code) : [...others, code];
  return next.length === 0 ? ['R-99-PN'] : next;
}

export function fiscalSummary(f: FiscalProfile): string {
  return `${PERSON_TYPE_LABEL[f.personType]} · ${VAT_REGIME_LABEL[f.vatRegime]} · ${f.responsibilities.join(', ')}`;
}

let catalog: Promise<Municipality[]> | null = null;

/** DIVIPOLA del DANE: se descarga una vez por sesión (unas 1.100 filas). */
export function loadMunicipalities(): Promise<Municipality[]> {
  if (!catalog) {
    catalog = http.get<Municipality[]>('/api/v1/public/municipalities').catch((err) => {
      catalog = null;
      throw err;
    });
  }
  return catalog;
}

export function fold(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}

/** Busca sin tildes por municipio o departamento; primero los que empiezan por el texto. */
export function searchMunicipalities(all: Municipality[], query: string, limit = 8): Municipality[] {
  const q = fold(query);
  if (!q) return [];
  if (/^\d{2,5}$/.test(q)) return all.filter((m) => m.code.startsWith(q)).slice(0, limit);
  const starts: Municipality[] = [];
  const contains: Municipality[] = [];
  for (const m of all) {
    const name = fold(m.name);
    const full = `${name} ${fold(m.departmentName)}`;
    if (name.startsWith(q)) starts.push(m);
    else if (full.includes(q)) contains.push(m);
  }
  const capitals = (a: Municipality, b: Municipality) => Number(b.code.endsWith('001')) - Number(a.code.endsWith('001'));
  return [...starts.sort(capitals), ...contains.sort(capitals)].slice(0, limit);
}

export function municipalityLabel(m: Municipality): string {
  return m.name === m.departmentName ? m.name : `${m.name} · ${m.departmentName}`;
}
