import { Link } from 'react-router';
import type { Plan } from '../api/types';
import { BRAND_NAME } from '../brand';
import { Icon } from '../components/ui';
import { annualSaving } from '../components/UpgradeOffer';
import { formatCop } from '../lib/format';
import { useApi } from '../lib/useApi';

/**
 * Página pública. Primero el momento que nos define (cobras y el tiquete sale solo), después
 * cómo funciona, para quién, precios reales del catálogo y lo que nos mueve.
 */
export function Landing() {
  const plans = useApi<Plan[]>('/api/v1/public/plans');
  const general = plans.data?.filter((p) => p.family === 'GENERAL') ?? [];
  const commerce = plans.data?.filter((p) => p.family === 'COMMERCE') ?? [];

  return (
    <div className="landing">
      <header className="l-header">
        <Link to="/" className="brand">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </Link>
        <nav className="l-nav" aria-label="Secciones">
          <a href="#como-funciona">Cómo funciona</a>
          <a href="#precios">Precios</a>
          <a href="#lo-que-nos-mueve">Lo que nos mueve</a>
        </nav>
        <div className="l-header-actions">
          <Link to="/entrar" className="btn btn-ghost">
            Entrar
          </Link>
          <Link to="/registro" className="btn btn-primary">
            Empieza gratis
          </Link>
        </div>
      </header>

      <main>
        <section className="l-hero" aria-labelledby="hero-h">
          <div className="l-hero-copy">
            <h1 id="hero-h">Vende, cobra y la factura sale sola.</h1>
            <p className="l-lead">
              Factura electrónica y tiquete POS para tiendas, fundaciones y profesionales en Colombia. Cobras con QR, el pago se confirma y el
              documento se emite sin que toques nada más.
            </p>
            <div className="row">
              <Link to="/registro" className="btn btn-primary btn-lg">
                Empieza gratis con 20 documentos
              </Link>
              <a href="#precios" className="btn btn-secondary btn-lg">
                Ver precios
              </a>
            </div>
            <p className="small muted">Sin tarjeta. Practica todo el flujo antes de facturar en real.</p>
          </div>
          <Receipt />
        </section>

        <section id="como-funciona" className="l-section" aria-labelledby="how-h">
          <h2 id="how-h">Así de corto es vender con factura</h2>
          <ol className="l-steps">
            <li>
              <h3>Armas la venta</h3>
              <p>Tocas tus productos o escribes el valor. Los impuestos se calculan solos.</p>
            </li>
            <li>
              <h3>Cobras</h3>
              <p>El cliente escanea el QR y paga desde su celular con Bre-B, tarjeta u otro medio que tengas activo. O recibes efectivo.</p>
            </li>
            <li>
              <h3>Sale el documento</h3>
              <p>Cuando el pago se confirma, el tiquete POS electrónico se emite solo. Si el cliente pide factura con sus datos, también.</p>
            </li>
            <li>
              <h3>Queda todo en orden</h3>
              <p>Cada documento con su estado ante la DIAN, listo para tu contador.</p>
            </li>
          </ol>
        </section>

        <section className="l-section l-audience" aria-labelledby="who-h">
          <h2 id="who-h">Pensado para quien no tiene tiempo de pelear con la DIAN</h2>
          <dl className="l-who">
            <div>
              <dt>Tiendas, restaurantes y droguerías</dt>
              <dd>Caja rápida desde el celular, cobro con QR y planes por volumen para el día a día de la caja.</dd>
            </div>
            <div>
              <dt>Fundaciones, refugios y ESAL</dt>
              <dd>Facturas por servicios, eventos y ventas de tu fundación, sin pagar un software pensado para empresas grandes.</dd>
            </div>
            <div>
              <dt>Profesionales independientes</dt>
              <dd>Una factura en un minuto con los datos de tu cliente guardados. Planes desde unas pocas facturas al mes.</dd>
            </div>
          </dl>
          <ul className="l-promises">
            <li>
              <strong>Nunca te quedas sin poder vender por sorpresa.</strong> Te avisamos antes de que se acabe tu cupo y eliges: subir de plan
              o seguir con documentos adicionales.
            </li>
            <li>
              <strong>Te acompañamos en la habilitación.</strong> Practicas sin riesgo y pasas a real con nosotros al lado.
            </li>
            <li>
              <strong>Tu equipo, con permisos.</strong> Cajeros que venden, contador que consulta, y solo tú tocas lo delicado.
            </li>
            <li>
              <strong>En el celular, como una app.</strong> Se instala desde el navegador, sin tiendas de aplicaciones.
            </li>
          </ul>
        </section>

        <section id="precios" className="l-section" aria-labelledby="price-h">
          <h2 id="price-h">Precios claros, en pesos colombianos</h2>
          <p className="l-sub">Pagando el año completo te ahorras meses. Puedes cambiar de plan cuando quieras.</p>
          {plans.loading ? (
            <p className="muted">Cargando precios…</p>
          ) : (
            <div className="l-prices">
              <PriceTable title="Para facturar" caption="Facturas electrónicas al mes" plans={general} />
              <PriceTable title="Para caja (POS)" caption="Documentos al mes, facturas y POS" plans={commerce} />
            </div>
          )}
          <p className="small muted">Empiezas con la prueba gratis: 20 documentos, sin fecha de vencimiento.</p>
        </section>

        <section id="lo-que-nos-mueve" className="l-mission" aria-labelledby="mission-h">
          <div className="l-mission-inner">
            <h2 id="mission-h">Cada suscripción le factura a alguien más</h2>
            <p>
              Los refugios de animales y las fundaciones también tienen que facturar, y casi nunca tienen con qué pagar un software. Por eso el
              20 % de cada suscripción va a un fondo solidario que cubre su facturación electrónica.
            </p>
            <p>
              Nacimos como aliados de AdoptaFácil, la red que conecta refugios con familias. Publicaremos cuánto entra al fondo y a quién llega,
              con cifras abiertas.
            </p>
          </div>
        </section>

        <section className="l-section" aria-labelledby="faq-h">
          <h2 id="faq-h">Preguntas frecuentes</h2>
          <div className="l-faq">
            <details>
              <summary>¿Mis facturas son válidas ante la DIAN?</summary>
              <p>
                Sí, cuando facturas en producción: los documentos se emiten a través de un proveedor tecnológico autorizado por la DIAN. Mientras
                practicas, todo está marcado como simulación y no tiene validez tributaria.
              </p>
            </details>
            <details>
              <summary>¿Qué incluye la prueba gratis?</summary>
              <p>20 documentos en producción, sin tarjeta y sin fecha de vencimiento. La práctica no gasta documentos.</p>
            </details>
            <details>
              <summary>¿Quién cobra el dinero de mis ventas con QR?</summary>
              <p>Tú. Conectas tu propia cuenta Bold y el dinero llega directo a ella. Nosotros solo creamos el cobro y emitimos el documento.</p>
            </details>
            <details>
              <summary>¿Quién ve mi información?</summary>
              <p>
                Solo tu organización y las personas que invites. La información de cada organización está aislada de las demás y las llaves de
                tus cuentas se guardan cifradas.
              </p>
            </details>
          </div>
        </section>

        <section className="l-final" aria-labelledby="final-h">
          <h2 id="final-h">Haz tu primera venta con factura hoy</h2>
          <Link to="/registro" className="btn btn-primary btn-lg">
            Empieza gratis
          </Link>
        </section>
      </main>

      <footer className="l-footer">
        <span className="brand">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </span>
        <nav aria-label="Legal" className="row">
          <Link to="/terminos">Términos y condiciones</Link>
          <Link to="/privacidad">Tratamiento de datos</Link>
          <Link to="/entrar">Entrar</Link>
        </nav>
        <p className="small">Hecho en Colombia.</p>
      </footer>
    </div>
  );
}

function PriceTable({ title, caption, plans }: { title: string; caption: string; plans: Plan[] }) {
  if (plans.length === 0) return null;
  return (
    <div className="l-price-block">
      <h3>{title}</h3>
      <table className="l-price-table">
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Plan</th>
            <th scope="col" className="num">Documentos al mes</th>
            <th scope="col" className="num">Mensual</th>
            <th scope="col" className="num">Anual</th>
          </tr>
        </thead>
        <tbody>
          {plans.map((p) => (
            <tr key={p.code}>
              <th scope="row">{p.name}</th>
              <td className="num">{p.quotaDocuments?.toLocaleString('es-CO') ?? 'Sin límite'}</td>
              <td className="num">{p.monthlyBillingAllowed && p.monthlyPrice ? formatCop(p.monthlyPrice) : 'Solo anual'}</td>
              <td className="num">
                {p.annualPrice ? formatCop(p.annualPrice) : '—'}
                {annualSaving(p) && <span className="l-saving">{annualSaving(p).replace(' · ', '')}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** El momento que nos define, en una animación: el tiquete se imprime solo al confirmarse el pago. */
function Receipt() {
  return (
    <figure className="l-receipt" aria-label="Ejemplo de un tiquete POS electrónico emitido al confirmarse un pago">
      <div className="receipt-paper">
        <p className="receipt-shop">Tienda Doña Rosa</p>
        <p className="receipt-meta">POS electrónico · CAJA 1043</p>
        <ul className="receipt-lines">
          <li>
            <span>2 × Gaseosa 400 ml</span>
            <span className="num">$ 5.000</span>
          </li>
          <li>
            <span>1 × Pan aliñado</span>
            <span className="num">$ 3.500</span>
          </li>
          <li>
            <span>1 × Leche 1 L</span>
            <span className="num">$ 4.200</span>
          </li>
        </ul>
        <p className="receipt-total">
          <span>Total</span>
          <span className="num">$ 12.700</span>
        </p>
        <p className="receipt-paid">Pagado con Bre-B</p>
        <p className="receipt-stamp">Emitido solo</p>
      </div>
      <figcaption className="small muted">Ejemplo ilustrativo.</figcaption>
    </figure>
  );
}
