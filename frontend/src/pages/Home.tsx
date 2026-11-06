import { Link } from 'react-router';
import type { Customer, InvoiceSummary, NumberingRange, Page, ProviderAccount, Quota } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Icon, Spinner, StatusBadge } from '../components/ui';
import { formatCop, formatDate } from '../lib/format';
import { useApi } from '../lib/useApi';

export function Home() {
  const { state, can } = useSession();
  const accounts = useApi<ProviderAccount[]>('/api/v1/provider-accounts');
  const ranges = useApi<NumberingRange[]>('/api/v1/numbering-ranges');
  const customers = useApi<Page<Customer>>('/api/v1/customers?limit=1');
  const invoices = useApi<Page<InvoiceSummary>>('/api/v1/invoices?limit=5');
  const quota = useApi<Quota>(can('usage') ? '/api/v1/subscription/quota' : null);

  const name = state.status === 'signed-in' ? state.me.organizationName : null;
  const activeAccount = accounts.data?.find((a) => a.active);
  const hasRange = !!activeAccount && !!ranges.data?.some((r) => r.environment === activeAccount.environment && r.remaining > 0);
  const steps = [
    { done: !!activeAccount, text: 'Elige cómo se envían tus facturas', to: '/configuracion' },
    { done: hasRange, text: 'Registra tu resolución de numeración', to: '/configuracion' },
    { done: (customers.data?.items.length ?? 0) > 0, text: 'Agrega tu primer cliente', to: '/clientes/nuevo' },
    { done: (invoices.data?.items.length ?? 0) > 0, text: 'Crea tu primera factura', to: '/facturas/nueva' },
  ];
  const setupLoading = accounts.loading || ranges.loading || customers.loading || invoices.loading;
  const pending = steps.filter((s) => !s.done).length;

  return (
    <>
      <div className="page-head">
        <div className="stack">
          <h1>Hola{name ? `, ${name}` : ''}</h1>
          <p>Este es el resumen de tu facturación.</p>
        </div>
        {can('write') && (
          <Link to="/facturas/nueva" className="btn btn-primary">
            {Icon.plus} Nueva factura
          </Link>
        )}
      </div>

      {activeAccount?.simulated && (
        <Alert tone="warn">
          Estás en <strong>modo simulación</strong>: puedes practicar todo el flujo, pero las facturas no se envían a la DIAN y no tienen validez
          tributaria. Cuando tu cuenta con el proveedor esté lista, la activamos en producción.
        </Alert>
      )}

      {!setupLoading && pending > 0 && (
        <section className="card stack" aria-labelledby="setup-title">
          <h2 id="setup-title">Para empezar a facturar</h2>
          <ol className="steps">
            {steps.map((s, i) => (
              <li key={s.text} className={`step${s.done ? ' done' : ''}`}>
                <span className="step-dot" aria-hidden="true">
                  {s.done ? '✓' : i + 1}
                </span>
                {s.done ? (
                  <span className="step-text">
                    {s.text} <span className="visually-hidden">(hecho)</span>
                  </span>
                ) : (
                  <Link to={s.to} className="step-text">
                    {s.text}
                  </Link>
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      {quota.data && <QuotaCard quota={quota.data} />}

      <section className="card tight" aria-labelledby="recent-title">
        <div className="row card-head">
          <h2 id="recent-title">Últimas facturas</h2>
          <Link to="/facturas" className="right small">
            Ver todas
          </Link>
        </div>
        {invoices.loading ? (
          <div className="empty">
            <Spinner />
          </div>
        ) : invoices.data && invoices.data.items.length > 0 ? (
          <ul className="list">
            {invoices.data.items.map((inv) => (
              <li key={inv.id}>
                <Link to={`/facturas/${inv.id}`} className="list-item">
                  <div className="grow">
                    <div className="title">{inv.customerName}</div>
                    <div className="sub">
                      {inv.number ?? 'Borrador'} · {formatDate(inv.issueDate)}
                    </div>
                  </div>
                  <div className="amount-col">
                    <span className="num">{formatCop(inv.payableAmount)}</span>
                    <StatusBadge status={inv.status} />
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <div className="empty">
            <p>Aún no has creado facturas.</p>
          </div>
        )}
      </section>
    </>
  );
}

function QuotaCard({ quota }: { quota: Quota }) {
  const isTrial = quota.planCode === 'TRIAL';
  const limit = quota.limit ?? null;
  const pct = limit ? Math.min(100, Math.round((quota.used / limit) * 100)) : 0;
  return (
    <section className="card stack" aria-labelledby="quota-title">
      <div className="row">
        <h2 id="quota-title">{isTrial ? 'Prueba gratis' : 'Consumo del mes'}</h2>
        <span className="right muted small">
          {isTrial ? 'Documentos en total' : `Hasta el ${formatDate(quota.windowEndExclusive)}`}
        </span>
      </div>
      {limit !== null ? (
        <>
          <div className="row">
            <strong className="num">
              {quota.used} de {limit}
            </strong>
            <span className="muted small">documentos emitidos en producción</span>
          </div>
          <div className={`meter${pct >= 80 ? ' warn' : ''}`} role="progressbar" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={quota.used} aria-label="Documentos usados">
            <span style={{ width: `${pct}%` }} />
          </div>
        </>
      ) : (
        <p>
          <strong>{quota.used}</strong> documentos emitidos en producción.
        </p>
      )}
      {quota.limitReached && (
        <Alert tone="warn">
          {isTrial
            ? 'Usaste los 20 documentos de la prueba. Elige un plan para seguir emitiendo.'
            : 'Llegaste al límite de tu plan este mes. Puedes mejorar tu plan o aceptar documentos adicionales.'}
        </Alert>
      )}
      <p className="muted small">Las facturas de simulación y habilitación no gastan documentos.</p>
    </section>
  );
}
