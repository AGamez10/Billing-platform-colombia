import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
import { SessionProvider } from './auth/session';
// Tipografías servidas desde el propio dominio (sin Google Fonts: privacidad y CSP estricta).
import '@fontsource-variable/bricolage-grotesque';
import '@fontsource-variable/public-sans';
import './styles.css';
import { dropPrerender } from './landing/prerenderSwap';

// La página pública prerenderizada solo corresponde a "/" (p. ej. si el service worker la
// devolvió sin conexión para otra ruta).
if (window.location.pathname !== '/') dropPrerender();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <SessionProvider>
        <App />
      </SessionProvider>
    </BrowserRouter>
  </StrictMode>,
);

// PWA: el service worker solo guarda la interfaz (nunca respuestas de la API ni datos tributarios).
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Sin service worker la app funciona igual, solo sin instalación offline.
    });
  });
}
