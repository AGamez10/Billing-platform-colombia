import { useEffect, useState } from 'react';
import { QrCode } from '../components/QrCode';

/**
 * La caja, para probarla sin registrarse. Todo ocurre en el navegador: no se cobra ni se
 * emite nada, y la pantalla lo dice.
 */
const PRODUCTS = [
  { id: 'gaseosa', name: 'Gaseosa 400 ml', price: 2500 },
  { id: 'pan', name: 'Pan aliñado', price: 3500 },
  { id: 'leche', name: 'Leche 1 L', price: 4200 },
  { id: 'cafe', name: 'Café 250 g', price: 9800 },
  { id: 'huevos', name: 'Huevos × 12', price: 8900 },
  { id: 'arroz', name: 'Arroz 1 kg', price: 3900 },
];

type Step = 'cart' | 'qr' | 'paying' | 'done';

const cop = (n: number) => `$ ${n.toLocaleString('es-CO')}`;

export function PosDemo() {
  const [cart, setCart] = useState<Record<string, number>>({ gaseosa: 2, pan: 1 });
  const [step, setStep] = useState<Step>('cart');
  const [number, setNumber] = useState(1);
  const lines = PRODUCTS.filter((p) => cart[p.id]).map((p) => ({ ...p, qty: cart[p.id]! }));
  const total = lines.reduce((sum, l) => sum + l.price * l.qty, 0);

  useEffect(() => {
    if (step !== 'paying') return;
    const t = window.setTimeout(() => setStep('done'), 1200);
    return () => window.clearTimeout(t);
  }, [step]);

  function add(id: string) {
    setCart((c) => ({ ...c, [id]: (c[id] ?? 0) + 1 }));
  }

  function remove(id: string) {
    setCart((c) => {
      const next = { ...c };
      if ((next[id] ?? 0) <= 1) delete next[id];
      else next[id] = next[id]! - 1;
      return next;
    });
  }

  function reset() {
    setCart({});
    setStep('cart');
    setNumber((n) => n + 1);
  }

  return (
    <div className="demo" aria-live="polite">
      <div className="demo-device">
        <div className="demo-bar">
          <span>Vender</span>
          <span className="demo-tag">Demostración</span>
        </div>
        {step === 'cart' && (
          <div className="demo-body">
            <div className="demo-products" role="group" aria-label="Productos">
              {PRODUCTS.map((p) => (
                <button key={p.id} type="button" className="demo-product" onClick={() => add(p.id)}>
                  <span>{p.name}</span>
                  <strong>{cop(p.price)}</strong>
                  {cart[p.id] ? <span className="demo-qty" aria-label={`${cart[p.id]} en la venta`}>{cart[p.id]}</span> : null}
                </button>
              ))}
            </div>
            <ul className="demo-lines">
              {lines.length === 0 && <li className="muted">Toca un producto para agregarlo.</li>}
              {lines.map((l) => (
                <li key={l.id}>
                  <button type="button" className="demo-minus" aria-label={`Quitar un ${l.name}`} onClick={() => remove(l.id)}>
                    −
                  </button>
                  <span className="grow">
                    {l.qty} × {l.name}
                  </span>
                  <span className="num">{cop(l.price * l.qty)}</span>
                </li>
              ))}
            </ul>
            <button type="button" className="btn btn-primary btn-block demo-charge" disabled={total === 0} onClick={() => setStep('qr')}>
              Cobrar con QR {cop(total)}
            </button>
          </div>
        )}
        {(step === 'qr' || step === 'paying') && (
          <div className="demo-body demo-center">
            <p className="demo-amount num">{cop(total)}</p>
            <QrCode value="Demostración: este código no cobra nada." label="Código QR de demostración" size={190} />
            <p className="muted small">En tu caja, el cliente lo escanea y paga en su celular.</p>
            <button type="button" className="btn btn-primary btn-block" disabled={step === 'paying'} onClick={() => setStep('paying')}>
              {step === 'paying' ? 'Confirmando el pago…' : 'Simular que el cliente pagó'}
            </button>
            <button type="button" className="btn btn-ghost" onClick={() => setStep('cart')}>
              Volver
            </button>
          </div>
        )}
        {step === 'done' && (
          <div className="demo-body demo-center">
            <div className="demo-check" aria-hidden="true">✓</div>
            <p className="demo-done-title">Pago recibido</p>
            <p className="muted small">El tiquete salió solo. Nadie tocó nada más.</p>
            <button type="button" className="btn btn-secondary btn-block" onClick={reset}>
              Nueva venta
            </button>
          </div>
        )}
      </div>

      <div className={`demo-receipt-slot${step === 'done' ? ' printed' : ''}`}>
        {step === 'done' ? (
          <div className="demo-receipt">
            <p className="receipt-shop">Tu negocio</p>
            <p className="receipt-meta">POS electrónico DEMO-{String(number).padStart(4, '0')}</p>
            <ul className="receipt-lines">
              {lines.map((l) => (
                <li key={l.id}>
                  <span>
                    {l.qty} × {l.name}
                  </span>
                  <span className="num">{cop(l.price * l.qty)}</span>
                </li>
              ))}
            </ul>
            <p className="receipt-total">
              <span>Total</span>
              <span className="num">{cop(total)}</span>
            </p>
            <p className="receipt-paid">Pagado con QR</p>
            <p className="receipt-stamp">Emitido</p>
            <p className="demo-note">Demostración: no se envió a la DIAN.</p>
          </div>
        ) : (
          <p className="demo-waiting">Aquí sale el tiquete cuando el pago se confirma.</p>
        )}
      </div>
    </div>
  );
}
