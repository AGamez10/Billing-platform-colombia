/**
 * La raíz "/" llega del servidor con la página pública ya dibujada en #seo-landing (para buscadores
 * y para que se vea antes de que cargue JavaScript). Cuando React dibuja la suya, o cuando hay
 * sesión y corresponde la app, el HTML estático se retira.
 */
export const PRERENDER_ID = 'seo-landing';

export function hasPrerender(): boolean {
  return typeof document !== 'undefined' && document.getElementById(PRERENDER_ID) !== null;
}

export function dropPrerender(): void {
  if (typeof document === 'undefined') return;
  document.getElementById(PRERENDER_ID)?.remove();
}
