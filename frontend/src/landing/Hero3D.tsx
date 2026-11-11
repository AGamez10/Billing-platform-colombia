import { useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * Lienzo 3D del inicio. Three.js se descarga aparte y solo después de que la página ya se ve;
 * mientras tanto (o sin WebGL, o con ahorro de datos) se muestra la versión estática.
 */
export function Hero3D({ fallback }: { fallback: ReactNode }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
    if (connection?.saveData || !supportsWebGL()) {
      setFailed(true);
      return;
    }
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let disposed = false;
    let handle: { dispose(): void } | null = null;
    const idle = (cb: () => void) =>
      'requestIdleCallback' in window
        ? (window as Window & { requestIdleCallback: (cb: () => void, o?: { timeout: number }) => number }).requestIdleCallback(cb, { timeout: 1200 })
        : window.setTimeout(cb, 300);
    idle(() => {
      import('./heroScene')
        .then((m) => m.mountHeroScene(canvas, { reducedMotion }))
        .then((h) => {
          if (disposed) {
            h.dispose();
            return;
          }
          handle = h;
          setReady(true);
        })
        .catch(() => setFailed(true));
    });
    return () => {
      disposed = true;
      handle?.dispose();
    };
  }, []);

  return (
    <div className={`hero-3d${ready ? ' ready' : ''}`}>
      {!failed && <canvas ref={canvasRef} className="hero-canvas" aria-hidden="true" />}
      <div className="hero-fallback" aria-hidden={ready || undefined}>
        {fallback}
      </div>
    </div>
  );
}

function supportsWebGL(): boolean {
  try {
    const c = document.createElement('canvas');
    return !!(c.getContext('webgl2') ?? c.getContext('webgl'));
  } catch {
    return false;
  }
}
