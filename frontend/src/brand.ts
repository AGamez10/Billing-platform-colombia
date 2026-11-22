/**
 * Nombre comercial (ADR-0037). Se puede cambiar en el build con VITE_BRAND_NAME para una marca
 * blanca; el logotipo con la tilde dorada solo se dibuja con el nombre propio.
 */
export const BRAND_NAME: string = import.meta.env.VITE_BRAND_NAME || 'Nítido';
export const BRAND_TAGLINE = 'Factura electrónica para todo Colombia';
