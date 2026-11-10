import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { http } from '../api/client';
import type { InvoiceSummary, NumberingRange, Page, PaymentAccount, ProviderAccount } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Loading, PageHead } from '../components/ui';
import { messageFor } from '../lib/errors';
import { ENVIRONMENT_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';

type SalesMode = 'COUNTER' | 'COMPANIES' | 'BOTH';

const MODE_KEY = 'setup.salesMode';

function storedMode(): SalesMode | null {
  try {
    const v = window.localStorage.getItem(MODE_KEY);
    return v === 'COUNTER' || v === 'COMPANIES' || v === 'BOTH' ? v : null;
  } catch {
    return null;
  }
}

function localDate(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Configuración guiada: de cuenta nueva a primer documento, en orden y en lenguaje del negocio.
 * Lo que ya está hecho se marca solo, leyendo el estado real de la cuenta.
 */
export function Setup() {
  const { can } = useSession();
  const accounts = useApi<ProviderAccount[]>('/api/v1/provider-accounts');
  const ranges = useApi<NumberingRange[]>('/api/v1/numbering-ranges');
  const invoices = useApi<Page<InvoiceSummary>>('/api/v1/invoices?limit=1');
  const payments = useApi<PaymentAccount>('/api/v1/payment-accounts');
  const [mode, setModeState] = useState<SalesMode | null>(storedMode);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (accounts.loading && !accounts.data) return <Loading />;

  const active = accounts.data?.find((a) => a.active);
  const environment = active?.environment;
  const wantsPos = mode === 'COUNTER' || mode === 'BOTH';
  const wantsInvoice = mode === 'COMPANIES' || mode === 'BOTH';
  const hasRange = (kind: 'INVOICE' | 'POS_EQUIVALENT') =>
    !!ranges.data?.some((r) => r.kind === kind && r.environment === environment && r.remaining > 0);
  const numberingDone = !!mode && !!environment && (!wantsPos || hasRange('POS_EQUIVALENT')) && (!wantsInvoice || hasRange('INVOICE'));
  const firstDone = (invoices.data?.items.length ?? 0) > 0;

  function setMode(m: SalesMode) {
    setModeState(m);
    try {
      window.localStorage.setItem(MODE_KEY, m);
    } catch {
      // sin almacenamiento local, se elige de nuevo la próxima vez
    }
  }

  async function run(key: string, action: () => Promise<unknown>, after: () => Promise<void>) {
    setBusy(key);
    setError(null);
    try {
      await action();
      await after();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(null);
    }
  }

  function startPractice() {
    void run('provider', () => http.post('/api/v1/provider-accounts', { providerId: 'mock', environment: 'SIMULATION' }), accounts.reload);
  }

  function createPracticeNumbering() {
    const kinds: { kind: 'INVOICE' | 'POS_EQUIVALENT'; prefix: string; resolution: string }[] = [];
    if (wantsInvoice && !hasRange('INVOICE')) kinds.push({ kind: 'INVOICE', prefix: 'PRAC', resolution: '18760000001' });
    if (wantsPos && !hasRange('POS_EQUIVALENT')) kinds.push({ kind: 'POS_EQUIVALENT', prefix: 'CAJA', resolution: '18760000002' });
    void run('ranges', async () => {
      for (const k of kinds) {
        await http.post('/api/v1/numbering-ranges', {
          kind: k.kind,
          environment: 'SIMULATION',
          resolutionNumber: k.resolution,
          prefix: k.prefix,
          rangeFrom: 1,
          rangeTo: 5000,
          validFrom: localDate(),
          validTo: localDate(365),
        });
      }
    }, ranges.reload);
  }

  return (
    <>
      <PageHead title="Configura tu cuenta" subtitle="Cuatro pasos, en este orden. Puedes practicar todo sin riesgo antes de facturar de verdad." />
      {error && <Alert tone="error">{error}</Alert>}

      <ol className="setup">
        <SetupStep n={1} done={!!mode} title="Cuéntanos cómo vendes">
          <p className="muted">Así sabemos qué documentos necesitas.</p>
          <div className="choice-row" role="radiogroup" aria-label="Cómo vendes">
            <Choice selected={mode === 'COUNTER'} onSelect={() => setMode('COUNTER')} title="En mostrador"
              text="Tienda, restaurante, droguería: el cliente paga y se lleva su tiquete POS electrónico." />
            <Choice selected={mode === 'COMPANIES'} onSelect={() => setMode('COMPANIES')} title="A empresas o con datos del cliente"
              text="Servicios, fundaciones, profesionales: factura electrónica con el NIT o la cédula del cliente." />
            <Choice selected={mode === 'BOTH'} onSelect={() => setMode('BOTH')} title="Las dos"
              text="Vendes en caja y también facturas a empresas." />
          </div>
        </SetupStep>

        <SetupStep n={2} done={!!active} title="Conecta la facturación electrónica">
          {active ? (
            <p>
              Listo: estás en <strong>{ENVIRONMENT_LABEL[active.environment].toLowerCase()}</strong>.
              {active.simulated && ' Todo funciona igual que en real, pero los documentos no van a la DIAN ni tienen validez tributaria.'}
            </p>
          ) : (
            <>
              <p>
                Tus documentos se emiten a través de un proveedor tecnológico autorizado por la DIAN. Hay tres etapas, y siempre sabrás en
                cuál estás:
              </p>
              <dl className="env-list">
                <dt>Práctica</dt>
                <dd>Para aprender. Nada sale hacia la DIAN.</dd>
                <dt>Habilitación</dt>
                <dd>Las pruebas que la DIAN exige antes de facturar en real. Te acompañamos en cada paso.</dd>
                <dt>Producción</dt>
                <dd>Documentos con validez tributaria.</dd>
              </dl>
              {can('provider') ? (
                <div>
                  <button type="button" className="btn btn-primary" disabled={busy !== null} onClick={startPractice}>
                    {busy === 'provider' ? 'Activando…' : 'Empezar en práctica'}
                  </button>
                </div>
              ) : (
                <p className="small muted">Este paso lo hace el dueño de la cuenta.</p>
              )}
            </>
          )}
        </SetupStep>

        <SetupStep n={3} done={numberingDone} title="Numeración de tus documentos" locked={!mode || !active}>
          <p className="muted">
            La DIAN autoriza los números que puedes usar mediante una resolución de numeración.
            {wantsPos && wantsInvoice ? ' Necesitas una para facturas y otra para el POS.' : ''}
          </p>
          {numberingDone ? (
            <p>Listo: ya tienes numeración vigente para {wantsPos && wantsInvoice ? 'facturas y POS' : wantsPos ? 'tu POS' : 'tus facturas'}.</p>
          ) : environment === 'SIMULATION' ? (
            can('manage') ? (
              <div className="stack tight-stack">
                <div>
                  <button type="button" className="btn btn-primary" disabled={busy !== null || !mode} onClick={createPracticeNumbering}>
                    {busy === 'ranges' ? 'Creando…' : 'Crear numeración de práctica'}
                  </button>
                </div>
                <p className="small muted">Para facturar en real, registras en Ajustes la resolución que te otorgue la DIAN.</p>
              </div>
            ) : (
              <p className="small muted">Este paso lo hace el dueño o un administrador.</p>
            )
          ) : (
            <p>
              Registra en <Link to="/configuracion">Ajustes</Link> la resolución tal como te la otorgó la DIAN: número, prefijo, rango y vigencia.
            </p>
          )}
        </SetupStep>

        <SetupStep n={4} done={firstDone} title="Tu primer documento" locked={!numberingDone}>
          <p className="muted">Hazlo ahora: en práctica no cuenta en tu plan.</p>
          <div className="row">
            {wantsPos && (
              <Link to="/vender" className="btn btn-primary">
                Hacer una venta
              </Link>
            )}
            {wantsInvoice && (
              <Link to="/facturas/nueva" className={`btn ${wantsPos ? 'btn-secondary' : 'btn-primary'}`}>
                Crear una factura
              </Link>
            )}
          </div>
        </SetupStep>
      </ol>

      <section className="card stack" aria-labelledby="extra-h">
        <h2 id="extra-h">Cuando quieras</h2>
        <ul className="link-list">
          <li>
            <Link to="/configuracion">{payments.data?.connected ? 'Cobros con QR conectados' : 'Cobra con QR (Bre-B, tarjetas, Nequi) y el POS sale solo'}</Link>
            <span className="small muted">Conecta tu cuenta Bold; el dinero llega directo a ti.</span>
          </li>
          <li>
            <Link to="/equipo">Invita a tu equipo</Link>
            <span className="small muted">Cajeros, contador o administradores, cada uno con su usuario.</span>
          </li>
          <li>
            <Link to="/productos/nuevo">Carga tus productos</Link>
            <span className="small muted">Con precio e impuesto, para vender con un toque.</span>
          </li>
        </ul>
      </section>
    </>
  );
}

function SetupStep({ n, title, done, locked = false, children }: { n: number; title: string; done: boolean; locked?: boolean; children: ReactNode }) {
  return (
    <li className={`setup-step${done ? ' done' : ''}${locked ? ' locked' : ''}`} aria-current={!done && !locked ? 'step' : undefined}>
      <span className="setup-marker" aria-hidden="true">{done ? '✓' : n}</span>
      <div className="setup-body">
        <h2>
          {title}
          {done && <span className="visually-hidden"> (hecho)</span>}
        </h2>
        {locked ? <p className="small muted">Completa los pasos anteriores.</p> : children}
      </div>
    </li>
  );
}

function Choice({ selected, onSelect, title, text }: { selected: boolean; onSelect: () => void; title: string; text: string }) {
  return (
    <button type="button" role="radio" aria-checked={selected} className={`choice${selected ? ' selected' : ''}`} onClick={onSelect}>
      <strong>{title}</strong>
      <span>{text}</span>
    </button>
  );
}
