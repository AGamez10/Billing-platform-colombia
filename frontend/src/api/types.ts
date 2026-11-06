// Formas de las respuestas de la API v1 (ver docs/API.md y /swagger-ui.html).
// Los montos llegan como números JSON con decimales exactos; la interfaz solo los muestra,
// nunca calcula impuestos: el servidor es la única fuente de verdad.

export type IdentificationType =
  | 'NIT'
  | 'CEDULA_CIUDADANIA'
  | 'CEDULA_EXTRANJERIA'
  | 'PASAPORTE'
  | 'TARJETA_IDENTIDAD';

export type Role = 'OWNER' | 'ADMIN' | 'BILLER' | 'VIEWER';

export interface Page<T> {
  items: T[];
  limit: number;
  offset: number;
  hasMore: boolean;
}

export interface Me {
  userId: string;
  organizationId: string;
  organizationName: string | null;
  role: Role;
  organizations: { id: string; name: string; role: Role }[];
}

export interface Customer {
  id: string;
  identificationType: IdentificationType;
  identificationNumber: string;
  checkDigit?: number;
  legalName: string;
  email?: string;
  phone?: string;
  address?: string;
  cityCode?: string;
  active: boolean;
  version: number;
}

export interface Product {
  id: string;
  sku?: string;
  name: string;
  kind: 'PRODUCT' | 'SERVICE';
  unitCode: string;
  unitPrice: string;
  taxRateCodes: string[];
  active: boolean;
  version: number;
}

export interface TaxRate {
  code: string;
  type: string;
  treatment: string;
  percentage: string;
  validFrom: string;
  validTo?: string;
  legalReference: string;
}

export type InvoiceStatus = 'DRAFT' | 'PENDING_SUBMISSION' | 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'FAILED';
export type Environment = 'SIMULATION' | 'HABILITATION' | 'PRODUCTION';

export interface InvoiceLine {
  lineNumber: number;
  description: string;
  unitCode: string;
  quantity: number;
  unitPrice: number;
  discountKind?: 'PERCENT' | 'AMOUNT';
  discountValue?: number;
  taxRateCodes: string[];
  grossAmount: number;
  discountAmount: number;
  taxableAmount: number;
  taxTotal: number;
  lineTotal: number;
}

export interface Invoice {
  id: string;
  status: InvoiceStatus;
  number?: string;
  issueDate: string;
  environment?: Environment;
  simulated: boolean;
  legallyValid: boolean;
  customer: {
    identificationType: IdentificationType;
    identificationNumber: string;
    checkDigit?: number;
    legalName: string;
    email?: string;
  };
  lines: InvoiceLine[];
  taxSubtotals: { code: string; type: string; percentage: number; taxableAmount: number; taxAmount: number }[];
  totals: { currency: string; lineExtensionAmount: number; taxTotal: number; payableAmount: number };
  documentKey?: string;
  validatedAt?: string;
  validationEnvironment?: Environment;
  rejectionReasons: string[];
  outcomeUncertain: boolean;
  lastError?: string;
  history: { from?: InvoiceStatus; to: InvoiceStatus; at: string; detail?: string }[];
}

export interface InvoiceSummary {
  id: string;
  status: InvoiceStatus;
  number?: string;
  issueDate: string;
  environment?: Environment;
  customerName: string;
  customerIdNumber: string;
  payableAmount: number;
  legallyValid: boolean;
}

export interface LineInput {
  description: string;
  unitCode: string;
  quantity: string;
  unitPrice: string;
  discount?: { kind: 'PERCENT' | 'AMOUNT'; value: string };
  taxRateCodes: string[];
}

export interface ProviderAccount {
  id: string;
  providerId: string;
  environment: Environment;
  active: boolean;
  simulated: boolean;
}

export interface NumberingRange {
  id: string;
  environment: Environment;
  resolutionNumber: string;
  prefix: string;
  rangeFrom: number;
  rangeTo: number;
  validFrom: string;
  validTo: string;
  nextNumber: number;
  remaining: number;
}

export interface Quota {
  planCode: string;
  limit?: number;
  used: number;
  windowStart: string;
  windowEndExclusive: string;
  limitReached: boolean;
  overageAvailable: boolean;
  overageUnitPrice?: number;
  overageAccepted: boolean;
}

export interface Plan {
  code: string;
  name: string;
  [key: string]: unknown;
}
