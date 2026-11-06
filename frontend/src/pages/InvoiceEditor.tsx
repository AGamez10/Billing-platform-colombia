import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { http } from '../api/client';
import type { Customer, Invoice, LineInput, Page, Product, TaxRate } from '../api/types';
import { Alert, Field, Icon, Loading, PageHead, SelectField } from '../components/ui';
import { messageFor } from '../lib/errors';
import { formatIdentification, parseDecimalInput } from '../lib/format';
import { useApi } from '../lib/useApi';
import { taxLabel, UNITS } from './Products';

interface LineState {
  key: number;
  productId: string;
  description: string;
  unitCode: string;
  quantity: string;
  unitPrice: string;
  discountKind: '' | 'PERCENT' | 'AMOUNT';
  discountValue: string;
  taxRateCodes: string[];
}

let nextKey = 1;
const blankLine = (): LineState => ({
  key: nextKey++,
  productId: '',
  description: '',
  unitCode: '94',
  quantity: '1',
  unitPrice: '',
  discountKind: '',
  discountValue: '',
  taxRateCodes: ['IVA_19'],
});

/** Valida en el navegador lo mínimo para no enviar basura; los totales e impuestos los calcula el servidor. */
export function toLineInputs(lines: LineState[]): { lines: LineInput[]; problem: string | null } {
  const out: LineInput[] = [];
  for (const [i, l] of lines.entries()) {
    const n = i + 1;
    if (!l.description.trim()) return { lines: [], problem: `Línea ${n}: escribe una descripción.` };
    const quantity = parseDecimalInput(l.quantity);
    if (!quantity || Number(quantity) <= 0) return { lines: [], problem: `Línea ${n}: la cantidad debe ser mayor que cero.` };
    const unitPrice = parseDecimalInput(l.unitPrice);
    if (unitPrice === null) return { lines: [], problem: `Línea ${n}: escribe el precio unitario.` };
    let discount: LineInput['discount'];
    if (l.discountKind) {
      const value = parseDecimalInput(l.discountValue);
      if (value === null) return { lines: [], problem: `Línea ${n}: escribe el valor del descuento.` };
      discount = { kind: l.discountKind, value };
    }
    out.push({ description: l.description.trim(), unitCode: l.unitCode, quantity, unitPrice, discount, taxRateCodes: l.taxRateCodes });
  }
  return { lines: out, problem: null };
}

export function InvoiceEditor() {
  const { id } = useParams();
  const isNew = !id;
  const navigate = useNavigate();
  const existing = useApi<Invoice>(isNew ? null : `/api/v1/invoices/${id}`);
  const customers = useApi<Page<Customer>>('/api/v1/customers?limit=100');
  const products = useApi<Page<Product>>('/api/v1/products?limit=100');
  const taxes = useApi<TaxRate[]>('/api/v1/public/tax-rates');
  const [customerId, setCustomerId] = useState('');
  const [lines, setLines] = useState<LineState[]>([blankLine()]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Editar un borrador: el servidor no devuelve el id del cliente, se ubica por identificación.
  useEffect(() => {
    const inv = existing.data;
    if (!inv || !customers.data) return;
    const match = customers.data.items.find(
      (c) => c.identificationType === inv.customer.identificationType && c.identificationNumber === inv.customer.identificationNumber,
    );
    if (match) setCustomerId(match.id);
    setLines(
      inv.lines.map((l) => ({
        key: nextKey++,
        productId: '',
        description: l.description,
        unitCode: l.unitCode,
        quantity: String(l.quantity),
        unitPrice: String(l.unitPrice),
        discountKind: l.discountKind ?? '',
        discountValue: l.discountValue === undefined ? '' : String(l.discountValue),
        taxRateCodes: l.taxRateCodes,
      })),
    );
  }, [existing.data, customers.data]);

  const selectedCustomer = useMemo(() => customers.data?.items.find((c) => c.id === customerId), [customers.data, customerId]);

  if (!isNew && existing.loading && !existing.data) return <Loading />;
  if (existing.data && existing.data.status !== 'DRAFT') {
    return <Alert tone="info">Esta factura ya fue emitida y no se puede modificar.</Alert>;
  }

  const update = (key: number, patch: Partial<LineState>) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  function pickProduct(key: number, productId: string) {
    const p = products.data?.items.find((x) => x.id === productId);
    if (!p) return update(key, { productId: '' });
    update(key, { productId, description: p.name, unitCode: p.unitCode, unitPrice: p.unitPrice, taxRateCodes: p.taxRateCodes });
  }

  function toggleTax(key: number, rate: TaxRate) {
    setLines((ls) =>
      ls.map((l) => {
        if (l.key !== key) return l;
        const sameType = new Set((taxes.data ?? []).filter((t) => t.type === rate.type).map((t) => t.code));
        const without = l.taxRateCodes.filter((c) => !sameType.has(c));
        return { ...l, taxRateCodes: l.taxRateCodes.includes(rate.code) ? without : [...without, rate.code] };
      }),
    );
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!customerId) return setError('Elige el cliente.');
    const { lines: payloadLines, problem } = toLineInputs(lines);
    if (problem) return setError(problem);
    setBusy(true);
    try {
      const body = { customerId, lines: payloadLines };
      const saved = isNew ? await http.post<Invoice>('/api/v1/invoices', body) : await http.put<Invoice>(`/api/v1/invoices/${id}`, body);
      navigate(`/facturas/${saved.id}`, { replace: true });
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  const noCustomers = customers.data && customers.data.items.length === 0;

  return (
    <>
      <PageHead title={isNew ? 'Nueva factura' : 'Editar borrador'} subtitle="Guardas un borrador, revisas los totales y luego la emites." />
      <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
        {error && <Alert tone="error">{error}</Alert>}

        <section className="card stack" aria-labelledby="cliente-title">
          <h2 id="cliente-title">Cliente</h2>
          {noCustomers ? (
            <Alert tone="info">
              Aún no tienes clientes. <Link to="/clientes/nuevo">Agrega uno</Link> y vuelve aquí.
            </Alert>
          ) : (
            <SelectField label="¿A quién le facturas?" value={customerId} onChange={(e) => setCustomerId(e.target.value)} required>
              <option value="">Elige un cliente</option>
              {(customers.data?.items ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.legalName} — {formatIdentification(c.identificationType, c.identificationNumber, c.checkDigit)}
                </option>
              ))}
            </SelectField>
          )}
          {selectedCustomer && !selectedCustomer.email && (
            <p className="small muted">Este cliente no tiene correo: no podremos enviarle la factura automáticamente.</p>
          )}
        </section>

        <section className="card stack" aria-labelledby="lineas-title">
          <h2 id="lineas-title">Qué vendiste</h2>
          {lines.map((l, i) => (
            <div key={l.key} className="line-card" role="group" aria-label={`Línea ${i + 1}`}>
              <div className="row">
                <strong>Línea {i + 1}</strong>
                {lines.length > 1 && (
                  <button type="button" className="icon-btn right" aria-label={`Quitar línea ${i + 1}`} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}>
                    {Icon.trash}
                  </button>
                )}
              </div>
              {(products.data?.items.length ?? 0) > 0 && (
                <SelectField label="Desde tu catálogo (opcional)" value={l.productId} onChange={(e) => pickProduct(l.key, e.target.value)}>
                  <option value="">Escribir a mano</option>
                  {products.data!.items.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
                </SelectField>
              )}
              <div className="line-grid">
                <div className="wide">
                  <Field label="Descripción" value={l.description} onChange={(e) => update(l.key, { description: e.target.value })} />
                </div>
                <Field label="Cantidad" inputMode="decimal" value={l.quantity} onChange={(e) => update(l.key, { quantity: e.target.value })} />
                <Field label="Precio unitario" inputMode="decimal" hint="Antes de impuestos" value={l.unitPrice} onChange={(e) => update(l.key, { unitPrice: e.target.value })} />
                <SelectField label="Unidad" value={l.unitCode} onChange={(e) => update(l.key, { unitCode: e.target.value })}>
                  {UNITS.map((u) => (
                    <option key={u.code} value={u.code}>
                      {u.label}
                    </option>
                  ))}
                </SelectField>
              </div>
              <div className="grid-2">
                <SelectField
                  label="Descuento"
                  value={l.discountKind}
                  onChange={(e) => update(l.key, { discountKind: e.target.value as LineState['discountKind'], discountValue: '' })}
                >
                  <option value="">Sin descuento</option>
                  <option value="PERCENT">Porcentaje</option>
                  <option value="AMOUNT">Valor en pesos</option>
                </SelectField>
                {l.discountKind && (
                  <Field
                    label={l.discountKind === 'PERCENT' ? 'Porcentaje de descuento' : 'Valor del descuento'}
                    inputMode="decimal"
                    value={l.discountValue}
                    onChange={(e) => update(l.key, { discountValue: e.target.value })}
                  />
                )}
              </div>
              <fieldset>
                <legend>Impuestos</legend>
                <div className="checks">
                  {(taxes.data ?? []).map((t) => (
                    <label key={t.code} className="check" title={t.legalReference}>
                      <input type="checkbox" checked={l.taxRateCodes.includes(t.code)} onChange={() => toggleTax(l.key, t)} />
                      {taxLabel(t)}
                    </label>
                  ))}
                </div>
              </fieldset>
            </div>
          ))}
          <button type="button" className="btn btn-secondary" onClick={() => setLines((ls) => [...ls, blankLine()])}>
            {Icon.plus} Agregar línea
          </button>
        </section>

        <div className="row">
          <button type="submit" className="btn btn-primary" disabled={busy || !!noCustomers}>
            {busy ? 'Guardando…' : 'Guardar y revisar totales'}
          </button>
          <Link to={isNew ? '/facturas' : `/facturas/${id}`} className="btn btn-secondary">
            Cancelar
          </Link>
        </div>
      </form>
    </>
  );
}
