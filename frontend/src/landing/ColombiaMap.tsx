import { useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react';
import type { Municipality } from '../api/types';
import { loadMunicipalities, searchMunicipalities } from '../lib/fiscal';
import map from './colombia-map.json';
import points from './municipios.json';

/**
 * Colombia dibujada con datos públicos: los departamentos de Natural Earth (dominio público) y
 * un punto por cada municipio de la DIVIPOLA del DANE, con sus coordenadas oficiales. San Andrés
 * y Providencia van en recuadro, como en los mapas del IGAC.
 */
type Point = [number, number, string];
const BANDS = 14;
const SMALL = new Set(['de', 'del', 'la', 'las', 'el', 'los', 'y', 'e']);

/** La DIVIPOLA viene en mayúsculas: "SAN JOSÉ DEL GUAVIARE" → "San José del Guaviare". */
function title(text: string): string {
  return text
    .toLowerCase()
    .split(/(\s+|-)/)
    .map((w, i) => (i > 0 && SMALL.has(w) ? w : w.charAt(0).toUpperCase() + w.slice(1)))
    .join('');
}

function project(lon: number, lat: number): [number, number] {
  if (lon < -80) {
    const i = map.inset;
    return [i.x + (lon - i.lon0) * i.k, i.y + (i.lat1 - lat) * i.k];
  }
  return [(lon - map.lon0) * map.k, (map.lat1 - lat) * map.k];
}

export function ColombiaMap() {
  const id = useId();
  const listId = `${id}-list`;
  const [query, setQuery] = useState('');
  const [all, setAll] = useState<Municipality[] | null>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [chosen, setChosen] = useState<Municipality | null>(null);
  const [loadError, setLoadError] = useState(false);
  const loading = useRef(false);

  // Los puntos se agrupan por franjas de norte a sur: la entrada se anima por franja, no por punto.
  const bands = useMemo(() => {
    const out: { x: number; y: number; code: string }[][] = Array.from({ length: BANDS }, () => []);
    for (const [lon, lat, code] of points as Point[]) {
      const [x, y] = project(lon, lat);
      const band = Math.min(BANDS - 1, Math.floor((lon < -80 ? 0 : y / map.height) * BANDS));
      out[band]!.push({ x, y, code });
    }
    return out;
  }, []);

  const byCode = useMemo(() => {
    const m = new Map<string, [number, number]>();
    for (const [lon, lat, code] of points as Point[]) m.set(code, project(lon, lat));
    return m;
  }, []);

  const results = all && query.trim() ? searchMunicipalities(all, query, 6) : [];
  const spot = chosen ? byCode.get(chosen.code) : undefined;

  function ensureCatalog() {
    if (all || loading.current) return;
    loading.current = true;
    loadMunicipalities()
      .then(setAll)
      .catch(() => setLoadError(true))
      .finally(() => (loading.current = false));
  }

  function choose(m: Municipality) {
    setChosen(m);
    setQuery(title(m.name));
    setOpen(false);
  }

  function onKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter' && results[active]) {
      e.preventDefault();
      choose(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  }

  const [bx, by, bw, bh] = map.inset.box as [number, number, number, number];

  return (
    <figure className="cmap">
      <svg
        className="cmap-svg"
        viewBox={`0 0 ${map.width} ${map.height}`}
        role="img"
        aria-label={`Mapa de Colombia con un punto por cada uno de sus ${points.length.toLocaleString('es-CO')} municipios`}
      >
        <g className="cmap-land">
          {map.departments.map((d, i) => (
            <path key={i} d={d} />
          ))}
        </g>
        <rect className="cmap-inset" x={bx} y={by} width={bw} height={bh} rx={3} />
        <path className="cmap-land cmap-islands" d={map.inset.d} />
        {bands.map((band, i) => (
          <g key={i} className="cmap-band" style={{ '--i': i } as CSSProperties}>
            {band.map((p) => (
              <circle key={p.code} cx={p.x} cy={p.y} r={1.9} />
            ))}
          </g>
        ))}
        {spot && (
          <g className="cmap-spot" transform={`translate(${spot[0]} ${spot[1]})`}>
            <circle r={14} className="cmap-spot-ring" />
            <circle r={4.5} className="cmap-spot-dot" />
          </g>
        )}
      </svg>

      <div className="cmap-search">
        <label htmlFor={id}>¿Dónde está tu negocio?</label>
        <div className="combo">
          <input
            id={id}
            role="combobox"
            aria-autocomplete="list"
            aria-expanded={open && results.length > 0}
            aria-controls={listId}
            aria-activedescendant={open && results[active] ? `${listId}-${results[active].code}` : undefined}
            autoComplete="off"
            placeholder="Escribe tu municipio"
            value={query}
            onFocus={() => {
              ensureCatalog();
              setOpen(true);
            }}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
              setOpen(true);
              setChosen(null);
            }}
            onBlur={() => window.setTimeout(() => setOpen(false), 120)}
            onKeyDown={onKey}
          />
          {open && results.length > 0 && (
            <ul id={listId} role="listbox" className="combo-list">
              {results.map((m, i) => (
                <li
                  key={m.code}
                  id={`${listId}-${m.code}`}
                  role="option"
                  aria-selected={i === active}
                  className={i === active ? 'active' : undefined}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    choose(m);
                  }}
                  onMouseEnter={() => setActive(i)}
                >
                  <span>{title(m.name)}</span>
                  <small>{title(m.departmentName)}</small>
                </li>
              ))}
            </ul>
          )}
        </div>
        <p className="cmap-answer" aria-live="polite">
          {chosen ? (
            <>
              Sí. Facturas desde <strong>{title(chosen.name)}</strong> ({title(chosen.departmentName)}); tu dirección fiscal lleva el código DANE{' '}
              <strong>{chosen.code}</strong>, el que pide la factura electrónica.
            </>
          ) : loadError ? (
            'No pudimos cargar los municipios. Recarga la página e intenta de nuevo.'
          ) : (
            <>Cada punto es un municipio de la DIVIPOLA del DANE. Puedes facturar desde todos.</>
          )}
        </p>
      </div>
    </figure>
  );
}
