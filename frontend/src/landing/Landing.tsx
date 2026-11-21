import { Link } from 'react-router';
import type { Plan } from '../api/types';
import { BRAND_NAME } from '../brand';
import { DocumentSheetView } from '../components/DocumentSheetView';
import { Icon } from '../components/ui';
import { annualSaving } from '../components/UpgradeOffer';
import { formatCop } from '../lib/format';
import { useApi } from '../lib/useApi';
import { ColombiaMap } from './ColombiaMap';
import { CostCalculator } from './CostCalculator';
import { PosDemo } from './PosDemo';
import { SAMPLE_SHEET } from './sampleSheet';
import './landing.css';

/**
 * Página pública. Primero la escala (el país entero, municipio por municipio), luego el producto
 * real (la factura tal como sale), la prueba con las manos (demo), a quién sirve, cómo se integra,
 * por qué confiar y cuánto cuesta con precios del catálogo.
 */
export function Landing() {
  const plans = useApi<Plan[]>('/api/v1/public/plans');
  const all = plans.data ?? [];
  const general = all.filter((p) => p.family === 'GENERAL');
  const commerce = all.filter((p) => p.family === 'COMMERCE');
  const cheapestMonthly = all
    .filter((p) => p.family !== 'TRIAL' && p.monthlyBillingAllowed && Number(p.monthlyPrice) > 0)
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
            <a href="#producto">Producto</a>
            <a href="#precios">Precios</a>
            <a href="#desarrolladores">Desarrolladores</a>
            <a href="#seguridad">Seguridad</a>
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
            <h1 id="hero-h">Factura electrónica para todo Colombia.</h1>
            <p className="lp-lead">
              Facturas, tiquetes POS y notas crédito con validez ante la DIAN, desde el celular o el computador. Para la tienda de Leticia y
              para la empresa de Barranquilla, con los mismos precios publicados.
            </p>
            <div className="lp-cta-row">
              <Link to="/registro" className="btn btn-primary btn-lg">
                Empieza gratis con 20 documentos
              </Link>
              <a href="#pruebalo" className="btn btn-lg lp-btn-outline">
                Prueba la caja aquí
              </a>
            </div>
            <dl className="lp-hero-facts">
              <div>
                <dt>{cheapestMonthly ? `Desde ${formatCop(cheapestMonthly)}` : 'Precios públicos'}</dt>
                <dd>al mes, con todo incluido</dd>
              </div>
              <div>
                <dt>Sin tarjeta</dt>
                <dd>para empezar</dd>
              </div>
              <div>
                <dt>Práctica ilimitada</dt>
                <dd>antes de facturar en real</dd>
              </div>
            </dl>
          </div>
          <ColombiaMap />
        </div>
      </section>

      <section id="producto" className="lp-section lp-product" aria-labelledby="product-h">
        <div className="lp-wrap lp-product-grid">
          <div className="lp-product-copy">
            <h2 id="product-h">Todo el ciclo de un documento, en un solo lugar</h2>
            <dl className="lp-cycle">
              <div>
                <dt>Emite</dt>
                <dd>Factura electrónica, tiquete POS y nota crédito, con tu numeración DIAN y los impuestos calculados por la plataforma.</dd>
              </div>
              <div>
                <dt>Cobra</dt>
                <dd>Tu cliente paga con QR en tu propia cuenta y el documento se emite solo cuando el pago se confirma.</dd>
              </div>
              <div>
                <dt>Entrega</dt>
                <dd>Por correo, por WhatsApp o con un QR que tu cliente escanea en la caja. Se ve e imprime sin crear cuenta.</dd>
              </div>
              <div>
                <dt>Corrige</dt>
                <dd>Anulaciones y devoluciones con nota crédito ligada a la factura original, sin rehacer nada a mano.</dd>
              </div>
              <div>
                <dt>Reporta</dt>
                <dd>Ventas del mes, impuestos y documentos validados, listos para tu contador en un archivo que abre en Excel.</dd>
              </div>
            </dl>
          </div>
          <figure className="lp-sheet">
            <div className="lp-sheet-frame">
              <DocumentSheetView sheet={SAMPLE_SHEET} />
            </div>
            <figcaption>Así sale cada factura: con CUFE, código QR, resolución y los datos tributarios de ambas partes. Ejemplo ilustrativo.</figcaption>
          </figure>
        </div>
      </section>

      <section id="pruebalo" className="lp-section lp-section-alt" aria-labelledby="demo-h">
        <div className="lp-wrap lp-split">
          <div className="lp-split-copy">
            <h2 id="demo-h">Pruébalo aquí, sin registrarte</h2>
            <p>
              Agrega productos, cobra con QR y simula el pago. El tiquete sale solo, como en tu caja. Es la misma pantalla que vas a usar todos
              los días, en el celular o en el computador.
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

      <section className="lp-section" aria-labelledby="who-h">
        <div className="lp-wrap">
          <h2 id="who-h">Del mostrador al departamento de contabilidad</h2>
          <dl className="lp-who">
            <div>
              <dt>Tiendas, restaurantes y droguerías</dt>
              <dd>Caja rápida en el celular, cobro con QR y cada venta con su tiquete POS, aunque entren cien clientes en una hora.</dd>
            </div>
            <div>
              <dt>Empresas de servicios</dt>
              <dd>Facturas entre empresas con los datos tributarios completos de cada cliente, equipo con permisos y reportes para el cierre.</dd>
            </div>
            <div>
              <dt>Profesionales independientes</dt>
              <dd>Una factura en un minuto, con tus clientes guardados y el dígito de verificación calculado por ti.</dd>
            </div>
            <div>
              <dt>Fundaciones, refugios y ESAL</dt>
              <dd>Facturas por servicios, eventos y ventas, sin pagar un software pensado para empresas grandes.</dd>
            </div>
            <div>
              <dt>Plataformas y software propio</dt>
              <dd>Emite desde tu tienda en línea, tu ERP o tu app con nuestra API y recibe el resultado de la DIAN en tu sistema.</dd>
            </div>
          </dl>
        </div>
      </section>

      <section id="desarrolladores" className="lp-dev" aria-labelledby="dev-h">
        <div className="lp-wrap lp-dev-grid">
          <div className="lp-dev-copy">
            <h2 id="dev-h">Factura desde tu propio sistema</h2>
            <p>Una API REST para crear y emitir documentos, y avisos firmados cuando la DIAN responde. Sin SDK obligatorio.</p>
            <ul className="lp-dev-list">
              <li>
                <strong>Claves de API con permisos.</strong> Cada integración solo hace lo que le diste: emitir, consultar o administrar
                clientes.
              </li>
              <li>
                <strong>Sin documentos duplicados.</strong> Si tu sistema reintenta con la misma clave de idempotencia, recibe el mismo documento.
              </li>
              <li>
                <strong>Avisos verificables.</strong> Cada webhook va firmado con HMAC-SHA256 y se reintenta durante casi dos días si tu servidor no
                responde.
              </li>
              <li>
                <strong>Tus datos, tu formato.</strong> Importa clientes y productos por CSV y exporta tus ventas cuando quieras.
              </li>
            </ul>
          </div>
          <div className="lp-code" role="group" aria-label="Ejemplo de uso de la API">
            <p className="lp-code-title">Emitir una factura</p>
            <pre>
              <code>{`POST /api/v1/invoices
Authorization: Bearer bpk_…
Idempotency-Key: pedido-48211

{
  "customerId": "6f1c…",
  "lines": [{
    "description": "Plan anual de soporte",
    "unitCode": "94",
    "quantity": 1,
    "unitPrice": "240000.00",
    "taxRateCodes": ["IVA19"]
  }]
}`}</code>
            </pre>
            <p className="lp-code-title">Aviso a tu servidor cuando la DIAN acepta</p>
            <pre>
              <code>{`X-Billing-Signature: t=1763740920,v1=5d2f…

{
  "type": "document.accepted",
  "data": {
    "number": "FE 1043",
    "legallyValid": true,
    "documentKey": "8f3a61c2…",
    "total": "285600.00"
  }
}`}</code>
            </pre>
          </div>
        </div>
      </section>

      <section id="seguridad" className="lp-section" aria-labelledby="sec-h">
        <div className="lp-wrap">
          <h2 id="sec-h">Tu información tributaria, protegida desde el diseño</h2>
          <div className="lp-sec-grid">
            <ul className="lp-sec-list">
              <li>
                <h3>Cada empresa, aislada</h3>
                <p>La base de datos impide por diseño que una organización vea datos de otra, aunque la aplicación se equivoque.</p>
              </li>
              <li>
                <h3>Verificación en dos pasos</h3>
                <p>Con tu app de autenticación. Una contraseña filtrada no basta para entrar a tu cuenta.</p>
              </li>
              <li>
                <h3>Llaves cifradas</h3>
                <p>Las credenciales de tus cuentas de pago se guardan cifradas con AES-256 y nunca se muestran de nuevo.</p>
              </li>
              <li>
                <h3>Copias cada día</h3>
                <p>Copia cifrada diaria fuera del servidor, con restauración probada de forma automática.</p>
              </li>
              <li>
                <h3>Si la DIAN se demora, nada se pierde</h3>
                <p>Cada documento queda en cola y se reintenta solo, sin duplicarse, hasta tener una respuesta.</p>
              </li>
              <li>
                <h3>Todo queda registrado</h3>
                <p>Quién emitió, anuló o cambió algo y cuándo. Ese registro no se puede editar.</p>
              </li>
            </ul>
            <aside className="lp-env">
              <h3>Nunca confundes una prueba con una factura real</h3>
              <ol>
                <li>
                  <strong>Práctica</strong>
                  <span>Aprende sin enviar nada a la DIAN.</span>
                </li>
                <li>
                  <strong>Habilitación</strong>
                  <span>El set de pruebas ante la DIAN, con nosotros al lado.</span>
                </li>
                <li>
                  <strong>Producción</strong>
                  <span>Solo aquí un documento tiene validez tributaria, y así lo dice.</span>
                </li>
              </ol>
            </aside>
          </div>
        </div>
      </section>

      <section id="precios" className="lp-section lp-section-alt" aria-labelledby="price-h">
        <div className="lp-wrap">
          <h2 id="price-h">Precios publicados, iguales para todo el país</h2>
          <p className="lp-sub">
            Todos los tipos de documento, usuarios ilimitados y API en todos los planes. Al llegar a tu límite te avisamos antes; nunca cobramos
            documentos extra sin que los aceptes.
          </p>
          {plans.data ? (
            <div className="lp-prices">
              <PriceTable title="Para facturar" plans={general} />
              <PriceTable title="Para caja (POS)" plans={commerce} />
            </div>
          ) : (
            <p className="muted">Cargando precios…</p>
          )}
          <h3 className="lp-calc-title">¿Cuál te sirve? Muévelo y lo ves</h3>
          {plans.data && <CostCalculator plans={all} />}
          <p className="small muted">Empiezas con la prueba gratis: 20 documentos en producción, sin tarjeta.</p>
        </div>
      </section>

      <section className="lp-section" aria-labelledby="switch-h">
        <div className="lp-wrap lp-switch">
          <h2 id="switch-h">¿Ya facturas con otro sistema? Cámbiate sin dejar de facturar</h2>
          <ol className="lp-switch-steps">
            <li>
              <h3>Practica sin soltar lo que tienes</h3>
              <p>Crea tu cuenta y usa la práctica mientras sigues facturando como hoy. Nada de lo que hagas aquí sale hacia la DIAN.</p>
            </li>
            <li>
              <h3>Trae tus datos</h3>
              <p>Importa clientes y productos desde un archivo de Excel, invita a tu equipo y conecta tu cuenta para cobrar con QR.</p>
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
            Los refugios de animales y las fundaciones también tienen que facturar, y casi nunca tienen con qué pagar un software. Por eso el 20
            % de cada suscripción va a un fondo solidario que cubre su facturación electrónica.
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
              <summary>¿Funciona en mi ciudad o municipio?</summary>
              <p>
                Sí. La plataforma trae los 1.122 municipios y áreas no municipalizadas de la DIVIPOLA del DANE, con el código que exige la
                factura electrónica en la dirección del emisor y del cliente.
              </p>
            </details>
            <details>
              <summary>¿Qué incluye la prueba gratis?</summary>
              <p>20 documentos en producción, sin tarjeta. La práctica no gasta documentos.</p>
            </details>
            <details>
              <summary>¿Quién recibe el dinero de mis ventas con QR?</summary>
              <p>Tú. Conectas tu propia cuenta Bold y el dinero llega directo a ella. Nosotros solo creamos el cobro y emitimos el documento.</p>
            </details>
            <details>
              <summary>¿Puedo emitir desde mi propio software?</summary>
              <p>Sí. Creas una clave de API con los permisos que quieras y tu sistema emite y consulta documentos; te avisamos por webhook cuando la DIAN responde.</p>
            </details>
            <details>
              <summary>¿Qué pasa si se acaba mi cupo del mes?</summary>
              <p>Te avisamos antes. Puedes subir de plan o, si tu plan lo permite y lo aceptas, seguir con documentos adicionales a un precio fijo.</p>
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
          <div className="lp-footer-brand">
            <span className="brand">
              {Icon.logo}
              <span>{BRAND_NAME}</span>
            </span>
            <p className="small">Facturación electrónica hecha en Colombia, para todo el país.</p>
          </div>
          <nav aria-label="Producto" className="lp-footer-col">
            <h2>Producto</h2>
            <a href="#producto">Qué incluye</a>
            <a href="#precios">Precios</a>
            <a href="#desarrolladores">API para desarrolladores</a>
            <a href="#seguridad">Seguridad</a>
          </nav>
          <nav aria-label="Cuenta" className="lp-footer-col">
            <h2>Cuenta</h2>
            <Link to="/registro">Crear cuenta</Link>
            <Link to="/entrar">Entrar</Link>
          </nav>
          <nav aria-label="Legal" className="lp-footer-col">
            <h2>Legal</h2>
            <Link to="/terminos">Términos y condiciones</Link>
            <Link to="/privacidad">Tratamiento de datos</Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}

function PriceTable({ title, plans }: { title: string; plans: Plan[] }) {
  if (plans.length === 0) return null;
  return (
    <div className="lp-price-block">
      <h3>{title}</h3>
      <table className="lp-price-table">
        <caption className="visually-hidden">{title}: documentos al mes y precio de cada plan</caption>
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
