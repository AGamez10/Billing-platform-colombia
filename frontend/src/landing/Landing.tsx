import { Link } from 'react-router';
import type { Plan } from '../api/types';
import { BRAND_NAME } from '../brand';
import { Icon } from '../components/ui';
import { annualSaving } from '../components/UpgradeOffer';
import { formatCop } from '../lib/format';
import { useApi } from '../lib/useApi';
import { CostCalculator } from './CostCalculator';
import { Hero3D } from './Hero3D';
import { PosDemo } from './PosDemo';
import './landing.css';

/**
 * Página pública. Orden pensado para quien hoy factura con otro sistema: primero ve la promesa
 * funcionando (3D), luego la prueba con sus manos (demo), sabe cuánto le cuesta con precios
 * reales (calculadora), entiende por qué confiar y qué nos mueve.
 */
export function Landing() {
  const plans = useApi<Plan[]>('/api/v1/public/plans');
  const all = plans.data ?? [];
  const general = all.filter((p) => p.family === 'GENERAL');
  const commerce = all.filter((p) => p.family === 'COMMERCE');
  const cheapestMonthly = all
    .filter((p) => p.monthlyBillingAllowed && p.monthlyPrice)
    .map((p) => Number(p.monthlyPrice))
    .sort((a, b) => a - b)[0];

  return (
    <div className="lp">
      <section className="lp-hero" aria-labelledby="hero-h">
        <header className="lp-header">
          <Link to="/" className="brand">
            {Icon.logo}
            <span>{BRAND_NAME}</span>
          </Link>
          <nav className="lp-nav" aria-label="Secciones">
            <a href="#pruebalo">Pruébalo</a>
            <a href="#precios">Precios</a>
            <a href="#confianza">Confianza</a>
            <a href="#lo-que-nos-mueve">Lo que nos mueve</a>
          </nav>
          <div className="lp-header-actions">
            <Link to="/entrar" className="btn lp-btn-quiet">
              Entrar
            </Link>
            <Link to="/registro" className="btn btn-primary">
              Empieza gratis
            </Link>
          </div>
        </header>

        <div className="lp-hero-grid">
          <div className="lp-hero-copy">
            <h1 id="hero-h">Vende, cobra y la factura sale sola.</h1>
            <p className="lp-lead">
              Factura electrónica y tiquete POS para el comercio colombiano. Tu cliente paga con QR y, en el momento en que el pago se confirma,
              el documento se emite. Sin digitar dos veces, sin esperar al final del día.
            </p>
            <div className="lp-cta-row">
              <Link to="/registro" className="btn btn-primary btn-lg">
                Empieza gratis con 20 documentos
              </Link>
              <a href="#pruebalo" className="btn btn-lg lp-btn-outline">
                Pruébalo aquí mismo
              </a>
            </div>
            <ul className="lp-hero-facts">
              <li>
                <strong>{cheapestMonthly ? formatCop(cheapestMonthly) : 'Precios públicos'}</strong>
                <span>{cheapestMonthly ? 'el plan mensual más bajo' : 'en esta página'}</span>
              </li>
              <li>
                <strong>Sin tarjeta</strong>
                <span>para empezar</span>
              </li>
              <li>
                <strong>Práctica ilimitada</strong>
                <span>antes de facturar en real</span>
              </li>
            </ul>
          </div>
          <Hero3D fallback={<StaticReceipt />} />
        </div>
      </section>

      <section id="confianza" className="lp-trust" aria-labelledby="trust-h">
        <div className="lp-wrap">
          <h2 id="trust-h" className="lp-trust-title">Confianza que se puede comprobar, no solo prometer</h2>
          <ul className="lp-trust-list">
            <li>
              <h3>Validez ante la DIAN</h3>
              <p>Tus documentos se emiten a través de un proveedor tecnológico autorizado por la DIAN, y ves el estado de cada uno: enviado, aceptado o rechazado, con el motivo.</p>
            </li>
            <li>
              <h3>Siempre sabes dónde estás</h3>
              <p>Práctica, habilitación o producción: cada documento lo dice. Nunca confundirás una prueba con una factura real.</p>
            </li>
            <li>
              <h3>Tu información, solo tuya</h3>
              <p>Cada organización está aislada en la base de datos, las llaves de tus cuentas se guardan cifradas y cada persona de tu equipo entra con su propio usuario.</p>
            </li>
            <li>
              <h3>El dinero llega directo a ti</h3>
              <p>Los cobros con QR van a tu propia cuenta Bold. Nosotros nunca tocamos el dinero de tus ventas.</p>
            </li>
          </ul>
        </div>
      </section>

      <section id="pruebalo" className="lp-section" aria-labelledby="demo-h">
        <div className="lp-wrap lp-split">
          <div className="lp-split-copy">
            <h2 id="demo-h">Pruébalo aquí, sin registrarte</h2>
            <p>
              Agrega productos, cobra con QR y simula el pago. El tiquete sale solo, como en tu caja. Es la misma pantalla que vas a usar
              todos los días, en el celular o en el computador.
            </p>
            <ol className="lp-steps-mini">
              <li>Toca los productos</li>
              <li>Cobra con QR</li>
              <li>El documento sale solo</li>
            </ol>
          </div>
          <PosDemo />
        </div>
      </section>

      <section className="lp-section lp-section-alt" aria-labelledby="who-h">
        <div className="lp-wrap">
          <h2 id="who-h">Hecho para quien no tiene tiempo de pelear con la DIAN</h2>
          <dl className="lp-who">
            <div>
              <dt>Tiendas, restaurantes y droguerías</dt>
              <dd>Caja rápida desde el celular, cobro con QR y planes por volumen pensados para el día a día de la caja.</dd>
            </div>
            <div>
              <dt>Fundaciones, refugios y ESAL</dt>
              <dd>Facturas por servicios, eventos y ventas de tu fundación, sin pagar un software pensado para empresas grandes.</dd>
            </div>
            <div>
              <dt>Profesionales independientes</dt>
              <dd>Una factura en un minuto, con los datos de tus clientes guardados y el dígito de verificación calculado por ti.</dd>
            </div>
          </dl>
          <ul className="lp-promises">
            <li>
              <strong>Nunca te quedas sin poder vender por sorpresa.</strong> Te avisamos antes de que se acabe tu cupo y eliges: subir de plan
              o seguir con documentos adicionales.
            </li>
            <li>
              <strong>Te acompañamos en la habilitación.</strong> Practicas sin riesgo y pasas a real con nosotros al lado.
            </li>
            <li>
              <strong>Tu equipo, con permisos.</strong> Cajeros que venden, contador que consulta y solo tú tocas lo delicado.
            </li>
            <li>
              <strong>En el celular, como una app.</strong> Se instala desde el navegador, sin pasar por tiendas de aplicaciones.
            </li>
          </ul>
        </div>
      </section>

      <section id="precios" className="lp-section" aria-labelledby="price-h">
        <div className="lp-wrap">
          <h2 id="price-h">¿Cuánto te cuesta? Muévelo y lo ves</h2>
          <p className="lp-sub">Con los precios reales del catálogo, sin letra pequeña. Cambias de plan cuando quieras.</p>
          {plans.data ? <CostCalculator plans={all} /> : <p className="muted">Cargando precios…</p>}
          <details className="lp-all-plans">
            <summary>Ver todos los planes</summary>
            <div className="lp-prices">
              <PriceTable title="Para facturar" caption="Facturas electrónicas al mes" plans={general} />
              <PriceTable title="Para caja (POS)" caption="Documentos al mes" plans={commerce} />
            </div>
          </details>
          <p className="small muted">Empiezas con la prueba gratis: 20 documentos, sin fecha de vencimiento.</p>
        </div>
      </section>

      <section className="lp-section lp-section-alt" aria-labelledby="switch-h">
        <div className="lp-wrap lp-switch">
          <h2 id="switch-h">¿Ya facturas con otro sistema?</h2>
          <ol className="lp-switch-steps">
            <li>
              <h3>Practica sin soltar lo que tienes</h3>
              <p>Crea tu cuenta y usa la práctica mientras sigues facturando como hoy. Nada de lo que hagas aquí sale hacia la DIAN.</p>
            </li>
            <li>
              <h3>Prepara tu caja</h3>
              <p>Carga tus productos y clientes, invita a tu equipo y conecta tu cuenta Bold para cobrar con QR.</p>
            </li>
            <li>
              <h3>Cambia cuando estés listo</h3>
              <p>Hacemos contigo la habilitación ante la DIAN y pasas a producción el día que tú decidas.</p>
            </li>
          </ol>
        </div>
      </section>

      <section id="lo-que-nos-mueve" className="lp-mission" aria-labelledby="mission-h">
        <div className="lp-wrap">
          <h2 id="mission-h">Cada suscripción le factura a alguien más</h2>
          <p className="lp-mission-lead">
            Los refugios de animales y las fundaciones también tienen que facturar, y casi nunca tienen con qué pagar un software. Por eso el
            20 % de cada suscripción va a un fondo solidario que cubre su facturación electrónica.
          </p>
          <ol className="lp-flow" aria-label="Cómo llega tu aporte">
            <li>
              <strong>Tu suscripción</strong>
              <span>pagas tu plan como cualquier software</span>
            </li>
            <li className="lp-flow-highlight">
              <strong>20 %</strong>
              <span>va al fondo solidario</span>
            </li>
            <li>
              <strong>Refugios y fundaciones</strong>
              <span>facturan sin pagar</span>
            </li>
          </ol>
          <p className="lp-mission-foot">
            Nacimos como aliados de AdoptaFácil, la red que conecta refugios con familias. Publicaremos cuánto entra al fondo y a quién llega,
            con cifras abiertas.
          </p>
        </div>
      </section>

      <section className="lp-section" aria-labelledby="faq-h">
        <div className="lp-wrap">
          <h2 id="faq-h">Preguntas frecuentes</h2>
          <div className="lp-faq">
            <details>
              <summary>¿Mis facturas son válidas ante la DIAN?</summary>
              <p>
                Sí, cuando facturas en producción: los documentos se emiten a través de un proveedor tecnológico autorizado por la DIAN. Mientras
                practicas, todo está marcado como práctica y no tiene validez tributaria.
              </p>
            </details>
            <details>
              <summary>¿Qué incluye la prueba gratis?</summary>
              <p>20 documentos en producción, sin tarjeta y sin fecha de vencimiento. La práctica no gasta documentos.</p>
            </details>
            <details>
              <summary>¿Quién recibe el dinero de mis ventas con QR?</summary>
              <p>Tú. Conectas tu propia cuenta Bold y el dinero llega directo a ella. Nosotros solo creamos el cobro y emitimos el documento.</p>
            </details>
            <details>
              <summary>¿Qué pasa si se acaba mi cupo del mes?</summary>
              <p>Te avisamos antes. Puedes subir de plan o, si tu plan lo permite y lo aceptas, seguir con documentos adicionales a un precio fijo.</p>
            </details>
            <details>
              <summary>¿Quién ve mi información?</summary>
              <p>
                Solo tu organización y las personas que invites, cada una con su usuario y sus permisos. La información de cada organización está
                aislada de las demás.
              </p>
            </details>
          </div>
        </div>
      </section>

      <section className="lp-final" aria-labelledby="final-h">
        <div className="lp-wrap">
          <h2 id="final-h">Tu próxima venta ya puede salir con factura</h2>
          <Link to="/registro" className="btn btn-primary btn-lg">
            Empieza gratis
          </Link>
        </div>
      </section>

      <footer className="lp-footer">
        <div className="lp-wrap lp-footer-inner">
          <span className="brand">
            {Icon.logo}
            <span>{BRAND_NAME}</span>
          </span>
          <nav aria-label="Legal" className="lp-footer-links">
            <Link to="/terminos">Términos y condiciones</Link>
            <Link to="/privacidad">Tratamiento de datos</Link>
            <Link to="/entrar">Entrar</Link>
          </nav>
          <p className="small">Hecho en Colombia.</p>
        </div>
      </footer>
    </div>
  );
}

function PriceTable({ title, caption, plans }: { title: string; caption: string; plans: Plan[] }) {
  if (plans.length === 0) return null;
  return (
    <div className="lp-price-block">
      <h3>{title}</h3>
      <table className="lp-price-table">
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
                {annualSaving(p) && <span className="lp-saving">{annualSaving(p).replace(' · ', '')}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Versión estática del hero (sin WebGL, con ahorro de datos o mientras carga el 3D). */
function StaticReceipt() {
  return (
    <figure className="l-receipt">
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
    </figure>
  );
}
