import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { http } from '../api/client';
import type { Page, Product, TaxRate } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Empty, Field, Icon, Loading, PageHead, SelectField, Spinner } from '../components/ui';
import { fieldErrors, messageFor } from '../lib/errors';
import { formatCop, parseDecimalInput } from '../lib/format';
import { useApi } from '../lib/useApi';

/** Unidades más comunes (códigos de la tabla de unidades de la DIAN, UN/ECE Rec. 20). */
export const UNITS: { code: string; label: string }[] = [
  { code: '94', label: 'Unidad' },
  { code: 'HUR', label: 'Hora' },
  { code: 'DAY', label: 'Día' },
  { code: 'MON', label: 'Mes' },
  { code: 'KGM', label: 'Kilogramo' },
  { code: 'LTR', label: 'Litro' },
  { code: 'MTR', label: 'Metro' },
];

export function taxLabel(rate: TaxRate): string {
  if (rate.treatment === 'EXEMPT') return `${rate.type} exento`;
  if (rate.treatment === 'EXCLUDED') return `${rate.type} excluido`;
  return `${rate.type} ${Number(rate.percentage)} %`;
}

export function ProductList() {
  const { can } = useSession();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQuery(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);
  const list = useApi<Page<Product>>(`/api/v1/products?limit=50${query ? `&q=${encodeURIComponent(query)}` : ''}`);

  return (
    <>
      <PageHead
        title="Productos y servicios"
        subtitle="Lo que vendes, con su precio e impuestos."
        action={
          can('write') && (
            <Link to="/productos/nuevo" className="btn btn-primary">
              {Icon.plus} Nuevo
            </Link>
          )
        }
      />
      <div className="field">
        <label htmlFor="pq" className="visually-hidden">
          Buscar producto
        </label>
        <input id="pq" type="search" placeholder="Buscar por nombre o referencia" value={q} onChange={(e) => setQ(e.target.value)} />
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
            {list.data.items.map((p) => (
              <li key={p.id}>
                <Link to={`/productos/${p.id}`} className="list-item">
                  <div className="grow">
                    <div className="title">{p.name}</div>
                    <div className="sub">
                      {p.kind === 'SERVICE' ? 'Servicio' : 'Producto'}
                      {p.sku ? ` · ${p.sku}` : ''}
                    </div>
                  </div>
                  <span className="num">{formatCop(p.unitPrice)}</span>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <Empty title={query ? 'Sin resultados' : 'Aún no tienes productos'}>
            {query ? 'Prueba con otro nombre.' : 'Guárdalos una vez con su precio e impuesto, y agrégalos a tus facturas con un toque.'}
          </Empty>
        )}
      </section>
    </>
  );
}

export function ProductEditor() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();
  const { can } = useSession();
  const existing = useApi<Product>(isNew ? null : `/api/v1/products/${id}`);
  const taxes = useApi<TaxRate[]>('/api/v1/public/tax-rates');
  const [form, setForm] = useState({ sku: '', name: '', kind: 'SERVICE' as Product['kind'], unitCode: '94', unitPrice: '', taxRateCodes: ['IVA_19'] });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const p = existing.data;
    if (p) setForm({ sku: p.sku ?? '', name: p.name, kind: p.kind, unitCode: p.unitCode, unitPrice: p.unitPrice, taxRateCodes: p.taxRateCodes });
  }, [existing.data]);

  if (!isNew && existing.loading && !existing.data) return <Loading />;
  if (!isNew && existing.error) return <Alert tone="error">{messageFor(existing.error)}</Alert>;
  const readOnly = !can('write');

  function toggleTax(rate: TaxRate) {
    setForm((f) => {
      // Una sola tarifa por tipo de impuesto (IVA o INC): elegir una reemplaza a la otra del mismo tipo.
      const sameType = new Set((taxes.data ?? []).filter((t) => t.type === rate.type).map((t) => t.code));
      const without = f.taxRateCodes.filter((c) => !sameType.has(c));
      return { ...f, taxRateCodes: f.taxRateCodes.includes(rate.code) ? without : [...without, rate.code] };
    });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    const payload = {
      sku: form.sku.trim() || undefined,
      name: form.name.trim(),
      kind: form.kind,
      unitCode: form.unitCode,
      unitPrice: parseDecimalInput(form.unitPrice) ?? form.unitPrice,
      taxRateCodes: form.taxRateCodes,
    };
    try {
      if (isNew) await http.post('/api/v1/products', payload);
      else await http.put(`/api/v1/products/${id}`, { version: existing.data!.version, product: payload });
      navigate('/productos');
    } catch (err) {
      setError(messageFor(err));
      setFields(fieldErrors(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHead title={isNew ? 'Nuevo producto o servicio' : form.name || 'Producto'} />
      <form className="card stack" onSubmit={(e) => void submit(e)} noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <Field label="Nombre" required disabled={readOnly} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} error={fields.name} />
        <div className="grid-2">
          <SelectField label="Tipo" disabled={readOnly} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as Product['kind'] })}>
            <option value="SERVICE">Servicio</option>
            <option value="PRODUCT">Producto</option>
          </SelectField>
          <Field label="Referencia (opcional)" disabled={readOnly} value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} />
        </div>
        <div className="grid-2">
          <Field
            label="Precio unitario antes de impuestos"
            inputMode="decimal"
            required
            disabled={readOnly}
            hint="Ejemplo: 50000 o 12500,50"
            value={form.unitPrice}
            onChange={(e) => setForm({ ...form, unitPrice: e.target.value })}
            error={fields.unitPrice}
          />
          <SelectField label="Unidad" disabled={readOnly} value={form.unitCode} onChange={(e) => setForm({ ...form, unitCode: e.target.value })}>
            {UNITS.map((u) => (
              <option key={u.code} value={u.code}>
                {u.label}
              </option>
            ))}
          </SelectField>
        </div>
        <fieldset disabled={readOnly}>
          <legend>Impuestos</legend>
          {taxes.loading ? (
            <Spinner />
          ) : (
            <div className="checks">
              {(taxes.data ?? []).map((t) => (
                <label key={t.code} className="check" title={t.legalReference}>
                  <input type="checkbox" checked={form.taxRateCodes.includes(t.code)} onChange={() => toggleTax(t)} />
                  {taxLabel(t)}
                </label>
              ))}
            </div>
          )}
          <p className="hint small muted">Si no sabes cuál aplica, pregúntale a tu contador. La tarifa queda guardada con su fundamento legal.</p>
        </fieldset>
        {!readOnly && (
          <div className="row">
            <button type="submit" className="btn btn-primary" disabled={busy}>
              {busy ? 'Guardando…' : 'Guardar'}
            </button>
            <Link to="/productos" className="btn btn-secondary">
              Cancelar
            </Link>
          </div>
        )}
      </form>
    </>
  );
}
