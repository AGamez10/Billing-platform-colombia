import { useId } from 'react';

/**
 * Motivos originales inspirados en la orfebrería precolombina de Colombia: anillos concéntricos,
 * granulado (los puntos que bordean las piezas de oro) y la greca escalonada. No reproducen
 * ninguna pieza en particular.
 */

/** Medallón: anillo, anillo punteado, granulado y centro. Decorativo. */
export function Medallion({ className = '', size = 320 }: { className?: string; size?: number }) {
  const dots = Array.from({ length: 24 }, (_, i) => {
    const a = (i / 24) * Math.PI * 2;
    return { x: 160 + Math.cos(a) * 146, y: 160 + Math.sin(a) * 146, i };
  });
  return (
    <svg className={`medallion ${className}`} width={size} height={size} viewBox="0 0 320 320" aria-hidden="true" focusable="false">
      <g className="medallion-spin">
        {dots.map((d) => (
          <circle key={d.i} cx={d.x} cy={d.y} r={4.2} />
        ))}
      </g>
      <circle cx={160} cy={160} r={120} className="medallion-ring" />
      <circle cx={160} cy={160} r={84} className="medallion-dash" />
      <circle cx={160} cy={160} r={40} className="medallion-core" />
    </svg>
  );
}

/** Banda de greca escalonada; se repite a lo ancho. Decorativa. */
export function Greca({ className = '' }: { className?: string }) {
  const id = `greca-${useId().replace(/[^a-zA-Z0-9]/g, '')}`;
  return (
    <svg className={`greca ${className}`} width="100%" height="28" aria-hidden="true" focusable="false" preserveAspectRatio="none">
      <defs>
        <pattern id={id} width="56" height="28" patternUnits="userSpaceOnUse">
          <path d="M0 24h8V16h8V8h8V0h8v8h8v8h8v8h8" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinejoin="miter" />
        </pattern>
      </defs>
      <rect width="100%" height="28" fill={`url(#${id})`} />
    </svg>
  );
}

/** Viñeta pequeña: el medallón en miniatura. */
export function Seal({ className = '' }: { className?: string }) {
  return (
    <svg className={`seal ${className}`} width="44" height="44" viewBox="0 0 44 44" aria-hidden="true" focusable="false">
      <circle cx="22" cy="22" r="19" fill="none" stroke="currentColor" strokeWidth="2" />
      <circle cx="22" cy="22" r="12.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeDasharray="3 2.4" />
      <circle cx="22" cy="22" r="5.5" fill="currentColor" />
    </svg>
  );
}
