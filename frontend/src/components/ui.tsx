import { useId, type ReactNode, type InputHTMLAttributes, type SelectHTMLAttributes } from 'react';
import type { Environment, InvoiceStatus } from '../api/types';
import { ENVIRONMENT_LABEL, STATUS_LABEL, STATUS_TONE } from '../lib/format';

export function Spinner({ label = 'Cargando' }: { label?: string }) {
  return (
    <span role="status" className="row">
      <span className="spinner" aria-hidden="true" />
      <span className="visually-hidden">{label}</span>
    </span>
  );
}

export function Loading() {
  return (
    <div className="center-screen">
      <Spinner />
    </div>
  );
}

export function Alert({ tone, children }: { tone: 'error' | 'info' | 'warn' | 'ok'; children: ReactNode }) {
  return (
    <div className={`alert alert-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <div>{children}</div>
    </div>
  );
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  hint?: string;
  error?: string;
};

export function Field({ label, hint, error, id, ...input }: FieldProps) {
  const auto = useId();
  const fieldId = id ?? auto;
  const describedBy = [hint ? `${fieldId}-hint` : null, error ? `${fieldId}-error` : null].filter(Boolean).join(' ');
  return (
    <div className="field">
      <label htmlFor={fieldId}>{label}</label>
      <input id={fieldId} aria-invalid={error ? true : undefined} aria-describedby={describedBy || undefined} {...input} />
      {hint && <span id={`${fieldId}-hint`} className="hint">{hint}</span>}
      {error && <span id={`${fieldId}-error`} className="error">{error}</span>}
    </div>
  );
}

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & { label: string; hint?: string; error?: string; children: ReactNode };

export function SelectField({ label, hint, error, id, children, ...select }: SelectProps) {
  const auto = useId();
  const fieldId = id ?? auto;
  return (
    <div className="field">
      <label htmlFor={fieldId}>{label}</label>
      <select id={fieldId} aria-invalid={error ? true : undefined} {...select}>
        {children}
      </select>
      {hint && <span className="hint">{hint}</span>}
      {error && <span className="error">{error}</span>}
    </div>
  );
}

export function StatusBadge({ status }: { status: InvoiceStatus }) {
  return <span className={`badge badge-${STATUS_TONE[status]}`}>{STATUS_LABEL[status]}</span>;
}

/** Todo lo que no es producción se marca siempre: nunca debe confundirse con una factura válida. */
export function EnvironmentBadge({ environment }: { environment?: Environment }) {
  if (!environment || environment === 'PRODUCTION') return null;
  return <span className="badge badge-sim">{ENVIRONMENT_LABEL[environment]} · sin validez tributaria</span>;
}

export function Empty({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <h2>{title}</h2>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

export function PageHead({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <div className="page-head">
      <div className="stack">
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export const Icon = {
  home: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round">
      <path d="M4 11l8-7 8 7v9H4z" />
      <path d="M10 20v-6h4v6" />
    </svg>
  ),
  invoice: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round">
      <path d="M6 3h9l3 3v15H6z" />
      <path d="M9 10h6M9 14h6M9 18h4" strokeLinecap="round" />
    </svg>
  ),
  people: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="9" cy="8" r="3.5" />
      <path d="M3 20c.8-3.5 3.2-5 6-5s5.2 1.5 6 5" />
      <path d="M16 4.5a3.5 3.5 0 010 7M18 15c1.6.6 2.6 2.2 3 5" />
    </svg>
  ),
  box: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round">
      <path d="M3 7l9-4 9 4v10l-9 4-9-4z" />
      <path d="M3 7l9 4 9-4M12 11v10" />
    </svg>
  ),
  gear: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" strokeLinecap="round" />
    </svg>
  ),
  cart: (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 4h2l2.4 11.2a2 2 0 002 1.6h7.7a2 2 0 002-1.5L21 8H6" />
      <circle cx="10" cy="20" r="1.3" />
      <circle cx="17" cy="20" r="1.3" />
    </svg>
  ),
  plus: (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
      <path d="M12 5v14M5 12h14" />
    </svg>
  ),
  trash: (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
    </svg>
  ),
  logo: (
    <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true">
      <rect x="2" y="2" width="28" height="28" rx="8" fill="#3DDC97" />
      <path d="M10 9h9l3 3v11H10z" fill="none" stroke="#0E1A2B" strokeWidth="2" strokeLinejoin="round" />
      <path d="M13 16l2.5 2.5L20 14" fill="none" stroke="#0E1A2B" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  ),
};
