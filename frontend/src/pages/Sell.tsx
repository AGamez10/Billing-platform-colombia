import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { http } from '../api/client';
import type { Customer, Invoice, NumberingRange, Page, Product, ProviderAccount, TaxRate } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, EnvironmentBadge, Icon, Loading, PageHead, SelectField, Spinner, StatusBadge } from '../components/ui';
import { centsToNumber, estimateTotalCents } from '../lib/estimate';
import { messageFor } from '../lib/errors';
import { formatCop, formatIdentification, isTerminal, parseDecimalInput } from '../lib/format';
import { useApi } from '../lib/useApi';

interface CartLine {
  key: string;
  description: string;
  unitCode: string;
  unitPrice: string; // decimal canónico
  quantity: number;
  taxRateCodes: string[];
}

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Venta rápida para comercio: tocar productos, ver el total y emitir el POS electrónico.
 * La clave de idempotencia se crea con el carrito y se conserva hasta que la venta se confirma:
 * si la red falla y se vuelve a tocar "Cobrar", el servidor devuelve la misma venta.
 */
export function Sell() {
  const { can } = useSession();
  const products = useApi<Page<Product>>('/api/v1/products?limit=100');
  const customers = useApi<Page<Customer>>('/api/v1/customers?limit=100');
  const taxes = useApi<TaxRate[]>('/api/v1/public/tax-rates');
  const ranges = useApi<NumberingRange[]>('/api/v1/numbering-ranges');
  const accounts = useApi<ProviderAccount[]>('/api/v1/provider-accounts');

  const [cart, setCart] = useState<CartLine[]>([]);
  const [customerId, setCustomerId] = useState('');
  const [manual, setManual] = useState({ description: '', price: '' });
  const [saleKey, setSaleKey] = useState(newKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sold, setSold] = useState<Invoice | null>(null);

  const active = accounts.data?.find((a) => a.active);
  const hasPosRange = !!ranges.data?.some((r) => r.kind === 'POS_EQUIVALENT' && r.environment === active?.environment && r.remaining > 0);
  const percentByCode = useMemo(() => new Map((taxes.data ?? []).map((t) => [t.code, t.percentage])), [taxes.data]);
  const totalCents = useMemo(
    () =>
      estimateTotalCents(
        cart.map((l) => ({
          quantity: String(l.quantity),
          unitPrice: l.unitPrice,
          taxPercents: l.taxRateCodes.map((c) => percentByCode.get(c) ?? '0'),
        })),
      ),
    [cart, percentByCode],
  );

  if (products.loading || ranges.loading || accounts.loading) return <Loading />;
  if (sold) return <Receipt sale={sold} onNext={() => { setSold(null); setCart([]); setCustomerId(''); setSaleKey(newKey()); }} />;

  const changeCart = (next: CartLine[]) => {
    setCart(next);
    setSaleKey(newKey()); // otro contenido = otra venta
    setError(null);
  };

  function addProduct(p: Product) {
    const existing = cart.find((l) => l.key === p.id);
    changeCart(
      existing
        ? cart.map((l) => (l.key === p.id ? { ...l, quantity: l.quantity + 1 } : l))
        : [...cart, { key: p.id, description: p.name, unitCode: p.unitCode, unitPrice: p.unitPrice, quantity: 1, taxRateCodes: p.taxRateCodes }],
    );
  }

  function addManual() {
    const price = parseDecimalInput(manual.price);
    if (!manual.description.trim() || price === null) {
      setError('Escribe qué vendiste y el precio.');
      return;
    }
    changeCart([...cart, { key: newKey(), description: manual.description.trim(), unitCode: '94', unitPrice: price, quantity: 1, taxRateCodes: ['IVA_19'] }]);
    setManual({ description: '', price: '' });
  }

  const setQty = (key: string, quantity: number) =>
    changeCart(quantity <= 0 ? cart.filter((l) => l.key !== key) : cart.map((l) => (l.key === key ? { ...l, quantity } : l)));

  async function charge() {
    setBusy(true);
    setError(null);
    try {
      const body = {
        customerId: customerId || undefined,
        lines: cart.map((l) => ({
          description: l.description,
          unitCode: l.unitCode,
          quantity: String(l.quantity),
          unitPrice: l.unitPrice,
          taxRateCodes: l.taxRateCodes,
        })),
      };
      const sale = await http.postWithHeaders<Invoice>('/api/v1/pos-sales', body, { 'Idempotency-Key': saleKey });
      setSold(sale);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHead title="Vender" subtitle="Toca los productos y cobra. El POS electrónico sale solo." />
      {!active ? (
        <Alert tone="info">
          Primero elige cómo se envían tus documentos en <Link to="/configuracion">Ajustes</Link>.
        </Alert>
      ) : !hasPosRange ? (
        <Alert tone="info">
          Para vender necesitas la resolución de numeración POS de la DIAN. Regístrala en <Link to="/configuracion">Ajustes</Link> como tipo "POS".
        </Alert>
      ) : null}
      {error && <Alert tone="error">{error}</Alert>}

      <section className="card stack" aria-labelledby="cat-h">
        <h2 id="cat-h">Productos</h2>
        {(products.data?.items.length ?? 0) > 0 ? (
          <div className="pos-grid">
            {products.data!.items.map((p) => (
              <button key={p.id} type="button" className="pos-tile" onClick={() => addProduct(p)}>
                <span className="pos-tile-name">{p.name}</span>
                <span className="num">{formatCop(p.unitPrice)}</span>
              </button>
            ))}
          </div>
        ) : (
          <p className="muted">
            Agrega tus productos en <Link to="/productos/nuevo">Productos</Link> para venderlos con un toque.
          </p>
        )}
        <div className="row pos-manual">
          <label className="visually-hidden" htmlFor="m-desc">Producto a mano</label>
          <input id="m-desc" placeholder="Otro producto" value={manual.description} onChange={(e) => setManual({ ...manual, description: e.target.value })} />
          <label className="visually-hidden" htmlFor="m-price">Precio</label>
          <input id="m-price" inputMode="decimal" placeholder="Precio" value={manual.price} onChange={(e) => setManual({ ...manual, price: e.target.value })} />
          <button type="button" className="btn btn-secondary" onClick={addManual}>
            {Icon.plus} Agregar
          </button>
        </div>
      </section>

      <section className="card stack" aria-labelledby="cart-h">
        <h2 id="cart-h">Venta</h2>
        {cart.length === 0 ? (
          <p className="muted">Aún no hay productos en esta venta.</p>
        ) : (
          <ul className="list">
            {cart.map((l) => (
              <li key={l.key} className="list-item">
                <div className="grow">
                  <div className="title">{l.description}</div>
                  <div className="sub">{formatCop(l.unitPrice)} c/u</div>
                </div>
                <div className="qty" role="group" aria-label={`Cantidad de ${l.description}`}>
                  <button type="button" className="icon-btn" aria-label="Quitar uno" onClick={() => setQty(l.key, l.quantity - 1)}>
                    −
                  </button>
                  <span className="num" aria-live="polite">{l.quantity}</span>
                  <button type="button" className="icon-btn" aria-label="Agregar uno" onClick={() => setQty(l.key, l.quantity + 1)}>
                    +
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <SelectField label="Cliente" value={customerId} onChange={(e) => { setCustomerId(e.target.value); setSaleKey(newKey()); }}
          hint="Si el cliente da su cédula o NIT, búscalo aquí; si no, la venta es a consumidor final.">
          <option value="">Consumidor final</option>
          {(customers.data?.items ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.legalName} — {formatIdentification(c.identificationType, c.identificationNumber, c.checkDigit)}
            </option>
          ))}
        </SelectField>
        <button
          type="button"
          className="btn btn-primary btn-block pos-charge"
          disabled={busy || cart.length === 0 || !hasPosRange || !can('finalize')}
          onClick={() => void charge()}
        >
          {busy ? 'Emitiendo…' : `Cobrar ${formatCop(centsToNumber(totalCents))}`}
        </button>
        <p className="small muted">Incluye impuestos. El total final lo confirma el sistema al emitir.</p>
      </section>
    </>
  );
}

function Receipt({ sale: initial, onNext }: { sale: Invoice; onNext: () => void }) {
  const [sale, setSale] = useState(initial);
  useEffect(() => {
    if (isTerminal(sale.status)) return;
    const t = setInterval(() => {
      http.get<Invoice>(`/api/v1/invoices/${sale.id}`).then(setSale).catch(() => undefined);
    }, 2000);
    return () => clearInterval(t);
  }, [sale.id, sale.status]);

  return (
    <>
      <PageHead title={`Venta ${sale.number ?? ''}`} subtitle={sale.customer.legalName} />
      <section className="card stack pos-receipt" aria-live="polite">
        <p className="pos-total num">{formatCop(sale.totals.payableAmount)}</p>
        <div className="row">
          <StatusBadge status={sale.status} />
          <EnvironmentBadge environment={sale.environment} />
          {!isTerminal(sale.status) && <Spinner label="Enviando" />}
        </div>
        {sale.status === 'REJECTED' && <Alert tone="error">El documento fue rechazado. Revísalo en Documentos.</Alert>}
        <div className="row">
          <button type="button" className="btn btn-primary" onClick={onNext}>
            Nueva venta
          </button>
          <Link to={`/facturas/${sale.id}`} className="btn btn-secondary">
            Ver detalle
          </Link>
        </div>
      </section>
    </>
  );
}
