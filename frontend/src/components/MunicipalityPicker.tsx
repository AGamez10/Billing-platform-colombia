import { useEffect, useId, useMemo, useState, type KeyboardEvent } from 'react';
import type { Municipality } from '../api/types';
import { loadMunicipalities, municipalityLabel, searchMunicipalities } from '../lib/fiscal';

interface Props {
  label?: string;
  value: string;
  onChange: (code: string) => void;
  disabled?: boolean;
  required?: boolean;
  error?: string;
  hint?: string;
}

/**
 * Selector de municipio (DIVIPOLA): se escribe el nombre, sin importar tildes, y se elige de la
 * lista. Guarda el código DANE de 5 dígitos; el texto libre nunca llega al servidor.
 */
export function MunicipalityPicker({ label = 'Municipio', value, onChange, disabled, required, error, hint }: Props) {
  const id = useId();
  const listId = `${id}-list`;
  const [all, setAll] = useState<Municipality[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);

  useEffect(() => {
    let alive = true;
    loadMunicipalities()
      .then((list) => alive && setAll(list))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  const selected = useMemo(() => all?.find((m) => m.code === value) ?? null, [all, value]);

  useEffect(() => {
    if (selected) setQuery(municipalityLabel(selected));
    else if (!value) setQuery('');
  }, [selected, value]);

  const options = useMemo(() => (all && open ? searchMunicipalities(all, query) : []), [all, open, query]);

  function choose(m: Municipality) {
    onChange(m.code);
    setQuery(municipalityLabel(m));
    setOpen(false);
  }

  function onKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((a) => Math.min(a + 1, Math.max(options.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter' && open && options[active]) {
      e.preventDefault();
      choose(options[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  }

  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(' ') || undefined;
  const typedButUnchosen = !!query && !selected && !open;

  return (
    <div className="field combo">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open && options.length > 0}
        aria-controls={listId}
        aria-activedescendant={open && options[active] ? `${listId}-${options[active].code}` : undefined}
        aria-invalid={error || typedButUnchosen ? true : undefined}
        aria-describedby={describedBy}
        autoComplete="off"
        placeholder={all ? 'Escribe el municipio, p. ej. Cali' : failed ? 'No se pudo cargar la lista' : 'Cargando municipios…'}
        disabled={disabled || !all}
        required={required}
        value={query}
        onChange={(e) => {
          setQuery(e.target.value);
          setActive(0);
          setOpen(true);
          if (value) onChange('');
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => window.setTimeout(() => setOpen(false), 120)}
        onKeyDown={onKey}
      />
      {open && options.length > 0 && (
        <ul id={listId} role="listbox" className="combo-list">
          {options.map((m, i) => (
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
              <span>{m.name}</span>
              <small>
                {m.departmentName} · {m.code}
              </small>
            </li>
          ))}
        </ul>
      )}
      {hint && <span id={`${id}-hint`} className="hint">{hint}</span>}
      {(error || typedButUnchosen) && (
        <span id={`${id}-error`} className="error">
          {error ?? 'Elige el municipio de la lista.'}
        </span>
      )}
    </div>
  );
}
