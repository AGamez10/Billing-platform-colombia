import { useEffect, useState } from 'react';

/** Token de un enlace de correo (`#t=...`). El fragmento nunca viaja al servidor. */
export function readLinkToken(hash: string): string | null {
  const match = /(?:^#|&)t=([A-Za-z0-9_-]{40,64})(?:&|$)/.exec(hash);
  return match?.[1] ?? null;
}

/** Lee el token una vez y lo borra de la barra de direcciones (no queda en el historial). */
export function useLinkToken(): string | null {
  const [token] = useState(() => readLinkToken(window.location.hash));
  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname);
  }, []);
  return token;
}
