import { lazy, Suspense } from 'react';
import { Link } from 'react-router';
import type { AttentionItem, Dashboard, InvoiceSummary, Page, Quota } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Icon, Spinner, StatusBadge } from '../components/ui';
import { UpgradeOffer } from '../components/UpgradeOffer';
import { documentTitle, formatCop, formatDate } from '../lib/format';
import { useApi } from '../lib/useApi';

const SalesChart = lazy(() => import('../components/SalesChart').then((m) => ({ default: m.SalesChart })));

const ENV_LABEL = { SIMULATION: 'práctica', HABILITATION: 'habilitación', PRODUCTION: 'producción' } as const;
const SEVERITY_LABEL = { CRITICAL: 'Urgente', WARNING: 'Pronto', INFO: 'Pendiente' } as const;

/**
 * Inicio: lo que requiere acción primero (con el botón para resolverlo), luego cómo van las
 * ventas del mes y los últimos documentos. Un solo llamado arma el resumen (/dashboard).
 */
export function Home() {
  const { state, can } = useSession();
  const dashboard = useApi<Dashboard>('/api/v1/dashboard');
  const invoices = useApi<Page<InvoiceSummary>>('/api/v1/invoices?limit=6');
  const quota = useApi<Quota>(can('usage') ? '/api/v1/subscription/quota' : null);

  const name = state.status === 'signed-in' ? state.me.organizationName : null;
  const d = dashboard.data;
  const practice = d?.environment === 'SIMULATION';
  const needsSetup = !!d?.attention.some((a) => a.code === 'PROVIDER_MISSING' || a.code === 'NUMBERING_MISSING');
  const hasSales = !!d && d.daily.some((x) => Number(x.total) !== 0);
  const monthName = d ? new Date(`${d.from}T12:00:00`).toLocaleDateString('es-CO', { month: 'long' }) : '';

  return (
    <>
      <div className="page-head">
        <div className="stack tight-stack">
          <h1>Hola{name ? `, ${name}` : ''}</h1>
          <p className="muted">{new Date().toLocaleDateString('es-CO', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
        </div>
        {can('write') && (
          <div className="row quick-actions">
            <Link to="/vender" className="btn btn-primary">
              {Icon.cart} Vender
            </Link>
            <Link to="/facturas/nueva" className="btn btn-secondary">
              {Icon.plus} Factura
            </Link>
            <Link to="/clientes/nuevo" className="btn btn-ghost">
              {Icon.people} Cliente
            </Link>
          </div>
        )}
      </div>

      {practice && (
        <Alert tone="warn">
          Estás en <strong>modo práctica</strong>: los documentos no van a la DIAN ni tienen validez tributaria, y no gastan tu plan.
        </Alert>
      )}

      {dashboard.error && <Alert tone="error">No pudimos cargar el resumen. Recarga la página.</Alert>}

      <section className="card stack" aria-labelledby="attention-title">
        <div className="row card-head">
          <h2 id="attention-title">Requiere tu atención</h2>
          {needsSetup && (
            <Link to="/empezar" className="right small">
              Configuración guiada
            </Link>
          )}
        </div>
        {!d ? (
          <div className="empty">
            <Spinner />
          </div>
        ) : d.attention.length === 0 ? (
          <p className="all-good">
            <span aria-hidden="true">✓</span> Todo en orden. Puedes facturar con tranquilidad.
          </p>
        ) : (
          <ul className="attention-list">
            {d.attention.map((a) => (
              <AttentionRow key={a.key} item={a} />
            ))}
          </ul>
        )}
      </section>

      {d && d.environment && (
        <section className="card stack" aria-labelledby="sales-title">
          <div className="row card-head">
            <h2 id="sales-title">Ventas de {monthName}</h2>
            <span className="right muted small">Validadas en {ENV_LABEL[d.environment]}</span>
          </div>
          <div className="kpis">
            <Kpi label="Total vendido" value={formatCop(d.sales.total)} hint={Number(d.sales.credited) > 0 ? `Ya descuenta ${formatCop(d.sales.credited)} en notas crédito` : undefined} />
            <Kpi label="Documentos validados" value={String(d.sales.documents)} />
            <Kpi label="Venta promedio" value={d.sales.documents ? formatCop(d.sales.averageTicket) : '—'} />
            {quota.data && <QuotaKpi quota={quota.data} />}
          </div>
          {hasSales ? (
            <Suspense fallback={<div className="chart-box" />}>
              <SalesChart daily={d.daily} />
            </Suspense>
          ) : (
            <p className="muted small">Cuando valides documentos este mes, aquí verás tus ventas día a día.</p>
          )}
        </section>
      )}

      {quota.data && quota.data.limit != null && quota.data.used >= quota.data.limit * 0.8 && (
        <UpgradeOffer onResolved={() => void quota.reload()} />
      )}

      <section className="card tight" aria-labelledby="recent-title">
        <div className="row card-head">
          <h2 id="recent-title">Últimos documentos</h2>
          <Link to="/facturas" className="right small">
            Ver todos
          </Link>
        </div>
        {invoices.loading && !invoices.data ? (
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
                      {documentTitle(inv.kind, inv.number)} · {formatDate(inv.issueDate)}
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
          <div className="empty stack">
            <p>Aún no tienes documentos.</p>
            {can('write') && (
              <Link to="/facturas/nueva" className="btn btn-secondary">
                Crear tu primera factura
              </Link>
            )}
          </div>
        )}
      </section>
    </>
  );
}

function AttentionRow({ item }: { item: AttentionItem }) {
  const tone = item.severity === 'CRITICAL' ? 'critical' : item.severity === 'WARNING' ? 'warning' : 'info';
  return (
    <li className={`attention attention-${tone}`}>
      <span className="attention-tag">{SEVERITY_LABEL[item.severity]}</span>
      <div className="grow">
        <strong>{item.title}</strong>
        <p className="small muted">{item.detail}</p>
      </div>
      <Link to={item.actionPath} className="btn btn-secondary btn-sm">
        {item.actionLabel}
      </Link>
    </li>
  );
}

function Kpi({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="kpi">
      <span className="kpi-label">{label}</span>
      <strong className="kpi-value num">{value}</strong>
      {hint && <span className="kpi-hint">{hint}</span>}
    </div>
  );
}

function QuotaKpi({ quota }: { quota: Quota }) {
  const limit = quota.limit ?? null;
  const pct = limit ? Math.min(100, Math.round((quota.used / limit) * 100)) : 0;
  return (
    <div className="kpi">
      <span className="kpi-label">{quota.planCode === 'TRIAL' ? 'Prueba gratis' : 'Plan del mes'}</span>
      <strong className="kpi-value num">{limit !== null ? `${quota.used} de ${limit}` : quota.used}</strong>
      {limit !== null && (
        <div className={`meter${pct >= 80 ? ' warn' : ''}`} role="progressbar" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={quota.used} aria-label="Documentos usados del plan">
          <span style={{ width: `${pct}%` }} />
        </div>
      )}
      <span className="kpi-hint">Solo cuentan los de producción</span>
    </div>
  );
}
