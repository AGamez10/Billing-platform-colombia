import type { DocumentSheet } from '../api/types';
import { nitCheckDigit } from '../lib/nit';

/**
 * Factura de ejemplo para mostrar la representación gráfica real de la app en la página pública.
 * Empresas inventadas; el QR lo dice si alguien lo escanea.
 */
export const SAMPLE_SHEET: DocumentSheet = {
  kind: 'INVOICE',
  number: 'TMP 1043',
  issueDate: '2026-11-20',
  validatedAt: '2026-11-20T10:42:00-05:00',
  environment: 'PRODUCTION',
  legallyValid: true,
  simulated: false,
  issuer: {
    identificationType: 'NIT',
    identificationNumber: '901234567',
    checkDigit: nitCheckDigit('901234567'),
    legalName: 'Taller El Pistón S.A.S.',
    email: 'facturas@tallerelpiston.co',
    address: 'Cra. 15 # 32-40',
    cityCode: '68001',
    cityName: 'Bucaramanga',
    departmentName: 'Santander',
    personTypeCode: '1',
    vatRegime: 'RESPONSIBLE',
    responsibilities: ['O-13'],
  },
  customer: {
    identificationType: 'NIT',
    identificationNumber: '900765432',
    checkDigit: nitCheckDigit('900765432'),
    legalName: 'Transportes del Oriente Ltda.',
    email: 'contabilidad@transoriente.co',
    address: 'Calle 45 # 8-12',
    cityCode: '68276',
    cityName: 'Floridablanca',
    departmentName: 'Santander',
    personTypeCode: '1',
    vatRegime: 'RESPONSIBLE',
    responsibilities: ['O-15'],
  },
  finalConsumer: false,
  authorization: { resolutionNumber: '18764000000000', prefix: 'TMP', rangeFrom: 1, rangeTo: 5000, validFrom: '2026-01-15', validTo: '2027-01-15' },
  lines: [
    line(1, 'Mantenimiento preventivo', 1, 180000),
    line(2, 'Cambio de aceite 20W-50', 2, 65000),
    line(3, 'Alineación y balanceo', 1, 90000),
  ],
  taxSubtotals: [{ code: 'IVA19', type: 'IVA', percentage: 19, taxableAmount: 400000, taxAmount: 76000 }],
  totals: { currency: 'COP', lineExtensionAmount: 400000, taxTotal: 76000, payableAmount: 476000 },
  documentKey: '8f3a61c2d9e04b7a95c1e6f2a8d4b0c37e9a12f5d6b8c0e3a7f4d1b9c2e5a8f06b3d7c1e9a4f2b8d5c0e7a3f6b1d9c4', // gitleaks:allow (CUFE ilustrativo de la página pública, no es un secreto)
  qrPayload: 'Ejemplo ilustrativo: esta factura no existe.',
  correction: null,
};

function line(n: number, description: string, quantity: number, unitPrice: number) {
  const gross = quantity * unitPrice;
  const tax = Math.round(gross * 0.19);
  return {
    lineNumber: n,
    description,
    unitCode: '94',
    quantity,
    unitPrice,
    taxRateCodes: ['IVA19'],
    grossAmount: gross,
    discountAmount: 0,
    taxableAmount: gross,
    taxTotal: tax,
    lineTotal: gross + tax,
  };
}
