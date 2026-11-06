import { ApiError } from '../api/client';

/**
 * Mensajes en español por código estable de la API. Nunca se muestra el `detail` técnico
 * (en inglés y pensado para integradores); un código sin mensaje usa uno genérico.
 */
const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: 'Revisa los campos marcados.',
  LEGAL_ACCEPTANCE_REQUIRED: 'Los términos se actualizaron. Recarga la página y acéptalos de nuevo.',
  INVALID_CREDENTIALS: 'Correo o contraseña incorrectos. Tras varios intentos la cuenta se bloquea unos minutos.',
  EMAIL_ALREADY_REGISTERED: 'Ya existe una cuenta con ese correo. Inicia sesión.',
  ORGANIZATION_ALREADY_REGISTERED: 'Esa identificación ya está registrada. Si es tu empresa, pide acceso a su dueño.',
  WEAK_PASSWORD: 'La contraseña es muy débil: usa al menos 12 caracteres; una frase de varias palabras funciona bien.',
  INVALID_NIT: 'El NIT o su dígito de verificación no son válidos.',
  INVALID_IDENTIFICATION: 'La identificación no es válida.',
  INVALID_EMAIL: 'El correo no es válido.',
  INVALID_NAME: 'El nombre no es válido.',
  CUSTOMER_ALREADY_EXISTS: 'Ya tienes un cliente con esa identificación.',
  CUSTOMER_NOT_FOUND: 'El cliente no existe o está inactivo.',
  PRODUCT_SKU_ALREADY_EXISTS: 'Ya tienes un producto con esa referencia.',
  INVALID_PRODUCT: 'Revisa los datos del producto.',
  INVALID_UNIT_PRICE: 'El precio no es válido.',
  INVALID_QUANTITY: 'La cantidad no es válida.',
  INVALID_LINE: 'Revisa las líneas de la factura.',
  INVALID_DISCOUNT: 'El descuento no es válido (porcentaje entre 0 y 100).',
  DISCOUNT_EXCEEDS_GROSS: 'El descuento no puede superar el valor de la línea.',
  DUPLICATE_TAX_TYPE: 'Una línea no puede tener dos tarifas del mismo impuesto.',
  TAX_RATE_NOT_FOUND: 'Una de las tarifas de impuesto no existe o no está vigente.',
  INVOICE_TOTAL_NOT_POSITIVE: 'El total de la factura debe ser mayor que cero.',
  TOO_MANY_LINES: 'La factura tiene demasiadas líneas.',
  DOCUMENT_WITHOUT_LINES: 'Agrega al menos una línea.',
  VERSION_CONFLICT: 'Alguien más modificó este registro. Recarga la página y vuelve a intentar.',
  INVALID_STATE_TRANSITION: 'Esta factura ya fue emitida y no se puede modificar.',
  NO_PROVIDER_CONFIGURED: 'Primero configura el proveedor de facturación en Configuración.',
  NUMBERING_RANGE_NOT_FOUND: 'No hay una resolución de numeración vigente para este ambiente. Regístrala en Configuración.',
  NUMBERING_RANGE_EXHAUSTED: 'Se agotó la numeración de tu resolución. Registra una nueva en Configuración.',
  NUMBERING_RESOLUTION_NOT_VALID: 'Tu resolución de numeración no está vigente para la fecha de la factura.',
  INVALID_NUMBERING_RANGE: 'Revisa los datos de la resolución (rango y fechas de vigencia).',
  PROVIDER_ENVIRONMENT_UNSUPPORTED: 'Ese proveedor no está disponible en ese ambiente.',
  PROVIDER_NOT_AVAILABLE: 'El proveedor de facturación no está disponible en este momento.',
  TRIAL_QUOTA_EXHAUSTED: 'Usaste los 20 documentos de la prueba gratis. Elige un plan para seguir emitiendo.',
  DOCUMENT_QUOTA_REACHED: 'Llegaste al límite de documentos de tu plan este mes.',
  DOCUMENT_QUOTA_EXCEEDED: 'Llegaste al límite de documentos de tu plan este mes.',
  SUBSCRIPTION_INACTIVE: 'Tu suscripción no está activa. Revisa el pago para seguir emitiendo.',
  SUBSCRIPTION_REQUIRED: 'Necesitas un plan activo para emitir.',
  OVERAGE_NOT_AVAILABLE: 'Los documentos adicionales no están disponibles en tu plan actual.',
  FORBIDDEN: 'Tu rol no tiene permiso para hacer esto.',
  UNAUTHENTICATED: 'Tu sesión terminó. Vuelve a iniciar sesión.',
  INVOICE_NOT_FOUND: 'La factura no existe.',
  PRODUCT_NOT_FOUND: 'El producto no existe.',
  PAYMENT_ACCOUNT_REQUIRED: 'Conecta tu cuenta Bold en Ajustes para cobrar con QR.',
  PAYMENT_CREDENTIALS_REJECTED: 'Bold rechazó esas llaves. Cópialas de nuevo desde tu panel de Bold (Integraciones → Llaves).',
  PAYMENT_CREDENTIALS_INVALID: 'Para cobros reales, pega también la llave secreta.',
  PAYMENT_GATEWAY_UNAVAILABLE: 'Bold no responde en este momento. Intenta de nuevo o cobra en efectivo.',
  PAYMENT_ENVIRONMENT_MISMATCH: 'El modo de Bold no coincide con el de tus documentos: llaves de prueba van con simulación o habilitación, y llaves reales con producción.',
  CHARGE_NOT_FOUND: 'Ese cobro no existe.',
  CHARGE_AMOUNT_MISMATCH: 'La venta se modificó después de cobrar: revisa el borrador antes de emitirlo.',
  RESOURCE_IN_USE: 'No se puede: ese registro está en uso (por ejemplo, la venta tiene un cobro).',
  IDEMPOTENCY_KEY_IN_PROGRESS: 'Esta venta todavía se está procesando. Espera un momento.',
  IDEMPOTENCY_KEY_REUSED: 'La venta cambió; vuelve a tocar Cobrar.',
  PLAN_NOT_FOUND: 'Ese plan ya no está disponible.',
  PLAN_NOT_AVAILABLE: 'Ese plan no se puede elegir.',
  PLAN_ALREADY_ACTIVE: 'Ya tienes ese plan activo.',
  PLAN_CYCLE_NOT_AVAILABLE: 'Ese plan no se vende con ese tipo de pago.',
  HTTP_429: 'Demasiados intentos seguidos. Espera un minuto y vuelve a intentar.',
};

export function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (MESSAGES[error.code]) return MESSAGES[error.code]!;
    if (error.status >= 500) return 'Algo falló de nuestro lado. Intenta de nuevo en un momento.';
    return 'No se pudo completar la operación. Revisa los datos e intenta de nuevo.';
  }
  return 'No hay conexión con el servidor. Revisa tu internet e intenta de nuevo.';
}

/** Errores de cuota: en vez de solo avisar, se ofrece mejorar el plan (y, si aplica, excedentes). */
export const QUOTA_CODES = new Set(['TRIAL_QUOTA_EXHAUSTED', 'DOCUMENT_QUOTA_REACHED', 'DOCUMENT_QUOTA_EXCEEDED']);

export function isQuotaError(error: unknown): boolean {
  return error instanceof ApiError && QUOTA_CODES.has(error.code);
}

/** Mensaje para un código que no vino como error HTTP (p. ej. el motivo de un cobro bloqueado). */
export function messageForCode(code: string): string {
  return MESSAGES[code] ?? 'Hay algo pendiente por resolver en esta venta.';
}

/** Errores de validación por campo, para mostrarlos junto a cada input. */
export function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {};
  const out: Record<string, string> = {};
  for (const f of error.fields) out[f.field] = 'Revisa este campo';
  return out;
}
