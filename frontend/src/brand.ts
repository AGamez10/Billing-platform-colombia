/**
 * Nombre comercial. Mientras el propietario lo define, se usa el nombre de trabajo; se cambia
 * en el build con VITE_BRAND_NAME (docker compose lo pasa como argumento de construcción).
 */
export const BRAND_NAME: string = import.meta.env.VITE_BRAND_NAME || 'Billing Platform';
