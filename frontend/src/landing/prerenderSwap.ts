/**
 * La raíz "/" llega del servidor con la página pública ya dibujada en #seo-landing (para buscadores
 * y para que se vea antes de que cargue JavaScript). Cuando React dibuja la suya, o cuando hay
 * sesión y corresponde la app, el HTML estático se retira.
 *
 * Si la persona alcanzó a escribir en un campo del HTML estático (por ejemplo, su municipio en el
 * buscador del mapa), lo escrito y el foco pasan al campo equivalente que dibujó React, para que no
 * se pierda nada en el cambio.
 */
export const PRERENDER_ID = 'seo-landing';

type Field = HTMLInputElement | HTMLTextAreaElement;

export function hasPrerender(): boolean {
  return typeof document !== 'undefined' && document.getElementById(PRERENDER_ID) !== null;
}

/** Identifica un campo por lo que lo nombra (no por su id, que React genera distinto en el cliente). */
function fieldKey(el: Field): string | null {
  if (el.name) return `name:${el.name}`;
  const aria = el.getAttribute('aria-label');
  if (aria) return `aria:${aria}`;
  const label = el.id ? el.ownerDocument.querySelector(`label[for="${CSS.escape(el.id)}"]`) : null;
  if (label?.textContent) return `label:${label.textContent.trim()}`;
  if (el.placeholder) return `ph:${el.placeholder}`;
  return null;
}

function fields(root: ParentNode): Field[] {
  return Array.from(root.querySelectorAll<Field>('input:not([type=hidden]), textarea'));
}

/** Escribe el valor como lo haría la persona, para que React lo vea en su onChange. */
function typeInto(el: Field, value: string): void {
  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')?.set?.call(el, value);
  el.dispatchEvent(new Event('input', { bubbles: true }));
}

export function dropPrerender(): void {
  if (typeof document === 'undefined') return;
  const pre = document.getElementById(PRERENDER_ID);
  if (!pre) return;

  const typed = new Map<string, string>();
  let focusedKey: string | null = null;
  for (const el of fields(pre)) {
    const key = fieldKey(el);
    if (!key) continue;
    const isToggle = el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio');
    if (!isToggle && el.value !== el.defaultValue) typed.set(key, el.value);
    if (document.activeElement === el) focusedKey = key;
  }

  pre.remove();
  if (typed.size === 0 && !focusedKey) return;

  const app = document.getElementById('root') ?? document.body;
  for (const el of fields(app)) {
    const key = fieldKey(el);
    if (!key) continue;
    const value = typed.get(key);
    if (value !== undefined && el.value === '') typeInto(el, value);
    if (key === focusedKey) {
      el.focus({ preventScroll: true });
      const end = el.value.length;
      try {
        el.setSelectionRange(end, end);
      } catch {
        // Algunos tipos de campo (number, email) no admiten selección.
      }
    }
  }
}
