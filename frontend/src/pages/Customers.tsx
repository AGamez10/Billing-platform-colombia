import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { http } from '../api/client';
import type { Customer, FiscalProfile, IdentificationType, Page } from '../api/types';
import { useSession } from '../auth/session';
import { FiscalFields } from '../components/FiscalFields';
import { MunicipalityPicker } from '../components/MunicipalityPicker';
import { defaultFiscal, fiscalSummary } from '../lib/fiscal';
import { Alert, Empty, Field, Icon, Loading, PageHead, SelectField, Spinner } from '../components/ui';
import { fieldErrors, messageFor } from '../lib/errors';
import { formatIdentification, ID_TYPE_LABEL } from '../lib/format';
import { cleanIdNumber, nitCheckDigit } from '../lib/nit';
import { useApi } from '../lib/useApi';

export function CustomerList() {
  const { can } = useSession();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQuery(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);
  const list = useApi<Page<Customer>>(`/api/v1/customers?limit=50${query ? `&q=${encodeURIComponent(query)}` : ''}`);

  return (
    <>
      <PageHead
        title="Clientes"
        subtitle="A quienes les facturas."
        action={
          can('write') && (
            <Link to="/clientes/nuevo" className="btn btn-primary">
              {Icon.plus} Nuevo cliente
            </Link>
          )
        }
      />
      <div className="field">
        <label htmlFor="q" className="visually-hidden">
          Buscar cliente
        </label>
        <input id="q" type="search" placeholder="Buscar por nombre o identificación" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <section className="card tight">
        {list.loading && !list.data ? (
          <div className="empty">
            <Spinner />
          </div>
        ) : list.error ? (
          <div className="card-head">
            <Alert tone="error">{messageFor(list.error)}</Alert>
          </div>
        ) : list.data && list.data.items.length > 0 ? (
          <ul className="list">
            {list.data.items.map((c) => (
              <li key={c.id}>
                <Link to={`/clientes/${c.id}`} className="list-item">
                  <div className="grow">
                    <div className="title">{c.legalName}</div>
                    <div className="sub">
                      {formatIdentification(c.identificationType, c.identificationNumber, c.checkDigit)}
                      {c.email ? ` · ${c.email}` : ''}
                    </div>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <Empty
            title={query ? 'Sin resultados' : 'Aún no tienes clientes'}
            action={
              !query &&
              can('write') && (
                <Link to="/clientes/nuevo" className="btn btn-primary">
                  Agregar el primero
                </Link>
              )
            }
          >
            {query ? 'Prueba con otro nombre o número.' : 'Guarda a tus clientes una vez y factúrales en segundos.'}
          </Empty>
        )}
      </section>
    </>
  );
}

const ID_TYPES: IdentificationType[] = ['NIT', 'CEDULA_CIUDADANIA', 'CEDULA_EXTRANJERIA', 'PASAPORTE', 'TARJETA_IDENTIDAD'];

interface CustomerFormState {
  identificationType: IdentificationType;
  identificationNumber: string;
  checkDigit: string;
  legalName: string;
  email: string;
  phone: string;
  address: string;
  cityCode: string;
  fiscal: FiscalProfile;
}

const EMPTY: CustomerFormState = {
  identificationType: 'NIT',
  identificationNumber: '',
  checkDigit: '',
  legalName: '',
  email: '',
  phone: '',
  address: '',
  cityCode: '',
  fiscal: defaultFiscal('NIT'),
};

export function CustomerEditor() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();
  const { can } = useSession();
  const existing = useApi<Customer>(isNew ? null : `/api/v1/customers/${id}`);
  const [form, setForm] = useState<CustomerFormState>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [fiscalTouched, setFiscalTouched] = useState(false);

  useEffect(() => {
    const c = existing.data;
    if (c) {
      setForm({
        identificationType: c.identificationType,
        identificationNumber: c.identificationNumber,
        checkDigit: c.checkDigit === undefined || c.checkDigit === null ? '' : String(c.checkDigit),
        legalName: c.legalName,
        email: c.email ?? '',
        phone: c.phone ?? '',
        address: c.address ?? '',
        cityCode: c.cityCode ?? '',
        fiscal: c.fiscal ?? defaultFiscal(c.identificationType),
      });
      setFiscalTouched(!!c.fiscal);
    }
  }, [existing.data]);

  if (!isNew && existing.loading && !existing.data) return <Loading />;
  if (!isNew && existing.error) return <Alert tone="error">{messageFor(existing.error)}</Alert>;

  const set = (key: keyof CustomerFormState) => (value: string) => setForm((f) => ({ ...f, [key]: value }));
  const isNit = form.identificationType === 'NIT';
  const expectedDv = isNit ? nitCheckDigit(cleanIdNumber(form.identificationNumber)) : null;
  const dvMismatch = isNit && form.checkDigit !== '' && expectedDv !== null && Number(form.checkDigit) !== expectedDv;
  const readOnly = !can('write');

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    const payload = {
      identificationType: form.identificationType,
      identificationNumber: cleanIdNumber(form.identificationNumber),
      checkDigit: isNit && form.checkDigit !== '' ? Number(form.checkDigit) : undefined,
      legalName: form.legalName.trim(),
      email: form.email.trim() || undefined,
      phone: form.phone.trim() || undefined,
      address: form.address.trim() || undefined,
      cityCode: form.cityCode || undefined,
      fiscal: { personType: form.fiscal.personType, vatRegime: form.fiscal.vatRegime, responsibilities: form.fiscal.responsibilities },
    };
    try {
      if (isNew) {
        await http.post<Customer>('/api/v1/customers', payload);
      } else {
        await http.put<Customer>(`/api/v1/customers/${id}`, { version: existing.data!.version, customer: payload });
      }
      navigate('/clientes');
    } catch (err) {
      setError(messageFor(err));
      setFields(fieldErrors(err));
    } finally {
      setBusy(false);
    }
  }

  async function deactivate() {
    if (!window.confirm('¿Desactivar este cliente? Sus facturas se conservan, pero no podrás facturarle hasta reactivarlo.')) return;
    try {
      await http.del(`/api/v1/customers/${id}`);
      navigate('/clientes');
    } catch (err) {
      setError(messageFor(err));
    }
  }

  return (
    <>
      <PageHead title={isNew ? 'Nuevo cliente' : form.legalName || 'Cliente'} />
      <form className="card stack" onSubmit={(e) => void submit(e)} noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <div className="grid-2">
          <SelectField
            label="Tipo de identificación"
            value={form.identificationType}
            disabled={readOnly || !isNew}
            onChange={(e) => {
              const t = e.target.value as IdentificationType;
              setForm((f) => ({ ...f, identificationType: t, fiscal: fiscalTouched ? f.fiscal : defaultFiscal(t) }));
            }}
          >
            {ID_TYPES.map((t) => (
              <option key={t} value={t}>
                {ID_TYPE_LABEL[t]}
              </option>
            ))}
          </SelectField>
          <div className="row" style={{ alignItems: 'flex-start', flexWrap: 'nowrap' }}>
            <Field
              label={isNit ? 'NIT' : 'Número'}
              inputMode={form.identificationType === 'PASAPORTE' ? 'text' : 'numeric'}
              required
              disabled={readOnly || !isNew}
              value={form.identificationNumber}
              onChange={(e) => set('identificationNumber')(e.target.value)}
              error={fields['customer.identificationNumber'] ?? fields.identificationNumber}
            />
            {isNit && (
              <Field
                label="DV"
                inputMode="numeric"
                maxLength={1}
                size={2}
                disabled={readOnly || !isNew}
                value={form.checkDigit}
                onChange={(e) => set('checkDigit')(e.target.value.replace(/\D/g, ''))}
                error={dvMismatch ? `Debería ser ${expectedDv}` : undefined}
              />
            )}
          </div>
        </div>
        <Field label="Nombre o razón social" required disabled={readOnly} value={form.legalName} onChange={(e) => set('legalName')(e.target.value)} error={fields.legalName} />
        <div className="grid-2">
          <Field label="Correo" type="email" hint="Aquí le llegará la factura." disabled={readOnly} value={form.email} onChange={(e) => set('email')(e.target.value)} error={fields.email} />
          <Field label="Teléfono" type="tel" disabled={readOnly} value={form.phone} onChange={(e) => set('phone')(e.target.value)} />
        </div>
        <div className="grid-2">
          <Field label="Dirección" disabled={readOnly} value={form.address} onChange={(e) => set('address')(e.target.value)} error={fields.address} />
          <MunicipalityPicker disabled={readOnly} value={form.cityCode} onChange={set('cityCode')} error={fields.cityCode}
            hint="Opcional en POS; recomendable para facturas." />
        </div>
        <details className="disclosure" open={isNew || !existing.data?.fiscal}>
          <summary>
            <strong>Datos tributarios</strong>
            <span className="small muted"> {fiscalSummary(form.fiscal)}</span>
          </summary>
          <FiscalFields
            whose="customer"
            disabled={readOnly}
            value={form.fiscal}
            onChange={(f) => {
              setFiscalTouched(true);
              setForm((prev) => ({ ...prev, fiscal: f }));
            }}
          />
        </details>
        {!readOnly && (
          <div className="row">
            <button type="submit" className="btn btn-primary" disabled={busy || dvMismatch}>
              {busy ? 'Guardando…' : 'Guardar'}
            </button>
            <Link to="/clientes" className="btn btn-secondary">
              Cancelar
            </Link>
            {!isNew && existing.data?.active && (
              <button type="button" className="btn btn-danger right" onClick={() => void deactivate()}>
                Desactivar
              </button>
            )}
          </div>
        )}
      </form>
    </>
  );
}
