import { useMemo } from 'react';
import { encode } from 'uqr';

/**
 * Código QR dibujado como SVG desde la matriz (sin innerHTML). Un solo <path> con un cuadro
 * por módulo oscuro: nítido en cualquier tamaño y legible por la cámara del cliente.
 */
export function QrCode({ value, label, size = 240 }: { value: string; label: string; size?: number }) {
  const { d, modules } = useMemo(() => {
    const qr = encode(value, { ecc: 'M', border: 0 });
    let path = '';
    qr.data.forEach((row, y) =>
      row.forEach((dark, x) => {
        if (dark) path += `M${x} ${y}h1v1h-1z`;
      }),
    );
    return { d: path, modules: qr.size };
  }, [value]);
  const quiet = 4; // zona silenciosa estándar de 4 módulos
  const box = modules + quiet * 2;
  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`${-quiet} ${-quiet} ${box} ${box}`}
      shapeRendering="crispEdges"
      className="qr"
    >
      <rect x={-quiet} y={-quiet} width={box} height={box} fill="#fff" />
      <path d={d} fill="#000" />
    </svg>
  );
}
