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
  fiscal?: FiscalProfile | null;
  active: boolean;
  version: number;
}

export type PersonType = 'LEGAL' | 'NATURAL';
export type VatRegime = 'RESPONSIBLE' | 'NOT_RESPONSIBLE';
export type TaxResponsibility = 'O-13' | 'O-15' | 'O-23' | 'O-47' | 'R-99-PN';

export interface FiscalProfile {
  personType: PersonType;
  vatRegime: VatRegime;
  responsibilities: TaxResponsibility[];
  personTypeCode?: string;
  vatRegimeCode?: string;
}

export interface Municipality {
  code: string;
  name: string;
  departmentCode: string;
  departmentName: string;
}

export interface OrganizationProfile {
  identificationType: IdentificationType;
  identificationNumber: string;
  checkDigit?: number | null;
  legalName: string;
  tradeName?: string | null;
  email?: string | null;
  phone?: string | null;
  fiscal?: FiscalProfile | null;
  address?: { line: string; cityCode: string; cityName?: string | null; departmentCode: string; departmentName?: string | null } | null;
  readyToIssue: boolean;
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

export type DocumentKind = 'INVOICE' | 'POS_EQUIVALENT';

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
  kind: DocumentKind;
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
    finalConsumer: boolean;
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
  kind: DocumentKind;
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
  kind: DocumentKind;
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

/** Plan público. Los precios llegan como texto decimal (nunca punto flotante). */
export interface Plan {
  code: string;
  name: string;
  family: 'TRIAL' | 'GENERAL' | 'COMMERCE';
  quotaDocuments?: number;
  quotaScope: 'MONTHLY' | 'TOTAL';
  maxUsers?: number;
  monthlyPrice?: string;
  monthlyBillingAllowed: boolean;
  annualPrice?: string;
  overageUnitPrice?: string;
}

export type BillingCycle = 'MONTHLY' | 'ANNUAL';

export interface PlanChange {
  pending: boolean;
  id?: string;
  fromPlan?: string;
  toPlan?: string;
  cycle?: BillingCycle;
  quotedPrice?: number;
  requestedAt?: string;
}

/** Cuenta de pasarela del comercio (Bold). Las llaves nunca vuelven del servidor. */
export interface PaymentAccount {
  connected: boolean;
  id?: string;
  gateway?: 'BOLD';
  mode?: 'TEST' | 'LIVE';
  /** Solo al conectar. */
  paymentMethods?: string[];
  /** Solo al conectar: se pega en el panel de Bold como URL de webhook. */
  webhookPath?: string;
}

export type ChargeStatus = 'PENDING' | 'PAID' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';

export interface Charge {
  id: string;
  status: ChargeStatus;
  amount: number;
  currency: 'COP';
  /** Mientras está pendiente: el link que abre el cliente (se muestra como QR). */
  checkoutUrl?: string;
  test: boolean;
  paymentMethod?: string;
  paidAt?: string;
  /** Pago recibido pero el documento aún no pudo salir; el código dice por qué. */
  issueBlockedCode?: string;
  sale: Invoice;
}

export interface TeamMember {
  userId: string;
  fullName: string;
  email: string;
  role: Role;
  since: string;
  you: boolean;
}

export interface TeamInvitation {
  id: string;
  email: string;
  role: Role;
  sentAt: string;
  expiresAt: string;
}

export interface Team {
  members: TeamMember[];
  invitations: TeamInvitation[];
  maxUsers?: number;
}

export interface InvitationPreview {
  organizationName: string;
  email: string;
  role: Role;
  existingAccount: boolean;
}

export interface SubscriptionInfo {
  planCode: string;
  status: 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'CANCELLED';
  periodStart: string;
  periodEnd: string;
  /** Mercado Pago configurado: el plan se paga en línea. */
  onlinePayments: boolean;
}

export interface SubscriptionCheckout {
  id: string;
  status: 'PENDING' | 'PAID' | 'EXPIRED';
  planCode: string;
  cycle: BillingCycle;
  amount: number;
  checkoutUrl?: string;
  /** Estado del último intento en Mercado Pago, para orientar si fue rechazado. */
  lastAttempt?: 'APPROVED' | 'PENDING' | 'REJECTED' | 'REFUNDED';
  paidAt?: string;
}

export interface SheetParty {
  identificationType: IdentificationType;
  identificationNumber: string;
  checkDigit?: number | null;
  legalName: string;
  email?: string | null;
  address?: string | null;
  cityCode?: string | null;
  cityName?: string | null;
  departmentName?: string | null;
  personTypeCode?: string | null;
  vatRegime?: VatRegime | null;
  responsibilities: TaxResponsibility[];
}

/** Representación gráfica de un documento validado (también la ve el comprador por enlace). */
export interface DocumentSheet {
  kind: DocumentKind;
  number: string;
  issueDate: string;
  validatedAt: string;
  environment: Environment;
  legallyValid: boolean;
  simulated: boolean;
  issuer: SheetParty;
  customer: SheetParty;
  finalConsumer: boolean;
  authorization: { resolutionNumber: string; prefix?: string | null; rangeFrom: number; rangeTo: number; validFrom: string; validTo: string };
  lines: InvoiceLine[];
  taxSubtotals: Invoice['taxSubtotals'];
  totals: Invoice['totals'];
  documentKey: string;
  qrPayload?: string | null;
}
