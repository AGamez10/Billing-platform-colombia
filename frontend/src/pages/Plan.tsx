import type { Plan as PlanData, Quota } from '../api/types';
import { useSession } from '../auth/session';
import { UpgradeOffer } from '../components/UpgradeOffer';
import { Alert, Loading, PageHead } from '../components/ui';
import { formatCop, formatDate } from '../lib/format';
import { useApi } from '../lib/useApi';

export function Plan() {
  const { can } = useSession();
  const quota = useApi<Quota>(can('usage') ? '/api/v1/subscription/quota' : null);
  const plans = useApi<PlanData[]>('/api/v1/public/plans');

  if (!can('usage')) {
    return (
      <>
        <PageHead title="Mi plan" />
        <Alert tone="info">Solo el dueño o un administrador ven el plan y los pagos de la cuenta.</Alert>
      </>
    );
  }
  if ((quota.loading && !quota.data) || (plans.loading && !plans.data)) return <Loading />;

  const q = quota.data;
  const current = plans.data?.find((p) => p.code === q?.planCode);
  const isTrial = q?.planCode === 'TRIAL';
  const limit = q?.limit ?? null;
  const pct = q && limit ? Math.min(100, Math.round((q.used / limit) * 100)) : 0;
  const reload = () => void quota.reload();

  return (
    <>
      <PageHead title="Mi plan" subtitle="Lo que tienes contratado, cuánto llevas y cómo cambiarlo." />

      <section className="card plan-now" aria-labelledby="now-h">
        <div className="plan-now-main">
          <h2 id="now-h">{isTrial ? 'Prueba gratis' : current?.name ?? q?.planCode}</h2>
          <p className="muted">
            {isTrial
              ? '20 documentos en total, sin fecha de vencimiento ni tarjeta.'
              : current
                ? `${current.quotaDocuments?.toLocaleString('es-CO') ?? 'Sin límite de'} documentos al mes${current.monthlyPrice ? ` · ${formatCop(current.monthlyPrice)} al mes` : ''}`
                : ''}
          </p>
        </div>
        {q && limit !== null && (
          <div className="plan-now-usage">
            <p>
              <strong className="num big-num">{q.used.toLocaleString('es-CO')}</strong>
              <span className="muted"> de {limit.toLocaleString('es-CO')} {isTrial ? 'usados' : 'este mes'}</span>
            </p>
            <div className={`meter${pct >= 80 ? ' warn' : ''}`} role="progressbar" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={q.used}
              aria-label="Documentos usados">
              <span style={{ width: `${pct}%` }} />
            </div>
            {!isTrial && <p className="small muted">El conteo vuelve a cero el {formatDate(q.windowEndExclusive)}.</p>}
          </div>
        )}
      </section>

      {q?.overageAccepted && (
        <Alert tone="info">
          Este mes aceptaste documentos adicionales a {formatCop(q.overageUnitPrice)} cada uno, después de los {limit} del plan.
        </Alert>
      )}
      <UpgradeOffer showAll title={isTrial ? 'Elige tu plan' : 'Cambiar de plan'} onResolved={reload} />

      <section className="card mission-card" aria-labelledby="mission-h">
        <h2 id="mission-h">Tu plan también factura por otros</h2>
        <p>
          El 20 % de cada suscripción va a un fondo solidario que paga la facturación electrónica de refugios de animales y fundaciones que no
          pueden costearla. Pronto verás aquí cuánto ha aportado tu organización y a quién llegó.
        </p>
      </section>
    </>
  );
}
