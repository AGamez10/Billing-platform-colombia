import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { http } from '../api/client';
import type { Customer, IdentificationType, Page } from '../api/types';
import { messageFor } from '../lib/errors';
import { defaultFiscal } from '../lib/fiscal';
import { formatIdentification, ID_TYPE_LABEL } from '../lib/format';
import { nitCheckDigit } from '../lib/nit';
import { Alert, Field, SelectField } from './ui';

interface Props {
  value: Customer | null;
  onChange: (customer: Customer | null) => void;
  label?: string;
}

/**
 * Elegir el cliente escribiendo su nombre o documento (búsqueda en el servidor, sin límite de
 * cantidad), o crearlo ahí mismo sin salir de la factura.
 */
export function CustomerPicker({ value, onChange, label = '¿A quién le facturas?' }: Props) {
  const id = useId();
  const listId = `${id}-list`;
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Customer[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [creating, setCreating] = useState(false);
  const seq = useRef(0);

  useEffect(() => {
    if (value) setQuery(value.legalName);
  }, [value]);

  useEffect(() => {
    if (!open) return;
    const mine = ++seq.current;
    const t = window.setTimeout(() => {
      const q = query.trim();
      http
        .get<Page<Customer>>(`/api/v1/customers?limit=8${q && q !== value?.legalName ? `&q=${encodeURIComponent(q)}` : ''}`)
        .then((page) => {
          if (mine === seq.current) {
            setResults(page.items);
            setActive(0);
          }
        })
        .catch(() => mine === seq.current && setResults([]));
    }, 180);
    return () => window.clearTimeout(t);
  }, [query, open, value?.legalName]);

  function choose(c: Customer) {
    onChange(c);
    setQuery(c.legalName);
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
    } else if (e.key === 'Enter' && open && results[active]) {
      e.preventDefault();
      choose(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  }

  if (creating) {
    return (
      <QuickCustomer
        initialName={value ? '' : query}
        onCancel={() => setCreating(false)}
        onCreated={(c) => {
          setCreating(false);
          choose(c);
        }}
      />
    );
  }

  return (
    <div className="stack tight-stack">
      <div className="field combo">
        <label htmlFor={id}>{label}</label>
        <input
          id={id}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={open && results.length > 0}
          aria-controls={listId}
          aria-activedescendant={open && results[active] ? `${listId}-${results[active].id}` : undefined}
          autoComplete="off"
          placeholder="Escribe el nombre, NIT o cédula"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
            if (value) onChange(null);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => window.setTimeout(() => setOpen(false), 120)}
          onKeyDown={onKey}
        />
        {open && results.length > 0 && (
          <ul id={listId} role="listbox" className="combo-list">
            {results.map((c, i) => (
              <li
                key={c.id}
                id={`${listId}-${c.id}`}
                role="option"
                aria-selected={i === active}
                className={i === active ? 'active' : undefined}
                onMouseDown={(e) => {
                  e.preventDefault();
                  choose(c);
                }}
                onMouseEnter={() => setActive(i)}
              >
                <span>{c.legalName}</span>
                <small>{formatIdentification(c.identificationType, c.identificationNumber, c.checkDigit)}</small>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="row">
        {value ? (
          <span className="small muted">
            {formatIdentification(value.identificationType, value.identificationNumber, value.checkDigit)}
            {value.email ? ` · ${value.email}` : ' · sin correo'}
          </span>
        ) : (
          query.trim().length >= 2 &&
          open === false && <span className="small muted">Elige un cliente de la lista o créalo.</span>
        )}
        <button type="button" className="btn btn-ghost btn-sm right" onClick={() => setCreating(true)}>
          + Nuevo cliente
        </button>
      </div>
    </div>
  );
}

/** Lo mínimo para facturar; el resto (dirección, datos tributarios) se completa en Clientes. */
function QuickCustomer({ initialName, onCreated, onCancel }: { initialName: string; onCreated: (c: Customer) => void; onCancel: () => void }) {
  const [type, setType] = useState<IdentificationType>('NIT');
  const [number, setNumber] = useState('');
  const [dv, setDv] = useState('');
  const [name, setName] = useState(initialName);
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const c = await http.post<Customer>('/api/v1/customers', {
        identificationType: type,
        identificationNumber: number.trim(),
        checkDigit: type === 'NIT' && dv !== '' ? Number(dv) : undefined,
        legalName: name.trim(),
        email: email.trim() || undefined,
        fiscal: defaultFiscal(type),
      });
      onCreated(c);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="quick-customer stack" role="group" aria-label="Nuevo cliente">
      <strong>Nuevo cliente</strong>
      {error && <Alert tone="error">{error}</Alert>}
      <div className="grid-2">
        <SelectField label="Tipo de documento" value={type} onChange={(e) => setType(e.target.value as IdentificationType)}>
          {(Object.keys(ID_TYPE_LABEL) as IdentificationType[]).map((t) => (
            <option key={t} value={t}>
              {ID_TYPE_LABEL[t]}
            </option>
          ))}
        </SelectField>
        <div className="row nit-row">
          <Field label="Número" inputMode={type === 'NIT' || type === 'CEDULA_CIUDADANIA' ? 'numeric' : 'text'} value={number}
            onChange={(e) => {
              setNumber(e.target.value);
              const digits = e.target.value.replace(/\D/g, '');
              const check = type === 'NIT' && /^\d{5,15}$/.test(digits) ? nitCheckDigit(digits) : null;
              if (check !== null) setDv(String(check));
            }} />
          {type === 'NIT' && <Field label="DV" inputMode="numeric" value={dv} onChange={(e) => setDv(e.target.value)} />}
        </div>
      </div>
      <Field label="Nombre o razón social" value={name} onChange={(e) => setName(e.target.value)} />
      <Field label="Correo para enviarle la factura" type="email" value={email} onChange={(e) => setEmail(e.target.value)} hint="Opcional" />
      <p className="small muted">Se guarda como persona {type === 'NIT' ? 'jurídica' : 'natural'} no responsable de IVA (R-99-PN). Puedes ajustar sus datos tributarios en Clientes.</p>
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={busy || !number.trim() || name.trim().length < 2} onClick={() => void save()}>
          {busy ? 'Guardando…' : 'Guardar cliente'}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}
