import { useState } from 'react';
import { http } from '../api/client';
import type { BillingCycle, Plan, PlanChange, Quota } from '../api/types';
import { useSession } from '../auth/session';
import { messageFor } from '../lib/errors';
import { formatCop } from '../lib/format';
import { useApi } from '../lib/useApi';
import { Alert, Spinner } from './ui';

/**
 * Oferta de mejora de plan cuando se acaba (o casi) la cuota. Primero se ofrecen los planes;
 * los documentos adicionales solo aparecen si el plan los admite, con aceptación expresa
 * (ADR-0014). Elegir un plan registra la solicitud con su precio; el plan se activa al pagar.
 */
export function UpgradeOffer({ onResolved, showAll = false, title }: { onResolved?: () => void; showAll?: boolean; title?: string }) {
  const { can } = useSession();
  const plans = useApi<Plan[]>('/api/v1/public/plans');
  const quota = useApi<Quota>('/api/v1/subscription/quota');
  const pending = useApi<PlanChange>('/api/v1/subscription/plan-change-requests/pending');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accept, setAccept] = useState(false);
  const canDecide = can('manage');

  if (plans.loading || quota.loading || pending.loading) return <Spinner label="Cargando planes" />;
  const all = plans.data ?? [];
  const q = quota.data;
  const current = all.find((p) => p.code === q?.planCode);
  const options = showAll ? all.filter((p) => p.family !== 'TRIAL' && p.code !== current?.code) : upgradeOptions(all, current);

  async function choose(plan: Plan, cycle: BillingCycle) {
    setBusy(`${plan.code}:${cycle}`);
    setError(null);
    try {
      await http.post<PlanChange>('/api/v1/subscription/plan-change-requests', { planCode: plan.code, cycle });
      await pending.reload();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(null);
    }
  }

  async function acceptOverage() {
    setBusy('overage');
    setError(null);
    try {
      await http.post('/api/v1/subscription/overage-consent');
      await quota.reload();
      onResolved?.();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(null);
    }
  }

  const chosen = pending.data?.pending ? all.find((p) => p.code === pending.data!.toPlan) : undefined;
  return (
    <section className="card stack upgrade" aria-labelledby="upgrade-h">
      <h2 id="upgrade-h">{title ?? (q?.planCode === 'TRIAL' ? 'Elige tu plan para seguir emitiendo' : 'Sube de plan y sigue emitiendo')}</h2>
      {error && <Alert tone="error">{error}</Alert>}
      {chosen && pending.data && (
        <Alert tone="ok">
          Elegiste <strong>{chosen.name}</strong> con pago {pending.data.cycle === 'ANNUAL' ? 'anual' : 'mensual'} por{' '}
          <strong>{formatCop(pending.data.quotedPrice)}</strong>. Te enviaremos el link de pago; el plan se activa cuando se confirme.
        </Alert>
      )}
      {!canDecide && <p className="muted">Pídele al dueño o al administrador de la cuenta que elija el plan.</p>}
      <div className="plan-grid">
        {options.map((p) => (
          <article key={p.code} className={`plan-card${chosen?.code === p.code ? ' chosen' : ''}`} aria-label={p.name}>
            <h3>{p.name}</h3>
            <p className="small muted">
              {p.quotaDocuments ? `${p.quotaDocuments.toLocaleString('es-CO')} documentos al mes` : 'Documentos sin límite'}
              {p.family === 'COMMERCE' ? ' · para caja (POS)' : ''}
            </p>
            {p.monthlyBillingAllowed && p.monthlyPrice && (
              <button type="button" className="btn btn-primary btn-block" disabled={!canDecide || !!busy} onClick={() => void choose(p, 'MONTHLY')}>
                {busy === `${p.code}:MONTHLY` ? 'Guardando…' : `${formatCop(p.monthlyPrice)} al mes`}
              </button>
            )}
            {p.annualPrice && (
              <button type="button" className="btn btn-secondary btn-block" disabled={!canDecide || !!busy} onClick={() => void choose(p, 'ANNUAL')}>
                {busy === `${p.code}:ANNUAL` ? 'Guardando…' : `${formatCop(p.annualPrice)} al año${annualSaving(p)}`}
              </button>
            )}
          </article>
        ))}
      </div>
      {q?.limitReached && q.overageAvailable && !q.overageAccepted && (
        <div className="stack overage">
          <h3>¿Prefieres no cambiar de plan este mes?</h3>
          <label className="consent">
            <input type="checkbox" checked={accept} disabled={!canDecide} onChange={(e) => setAccept(e.target.checked)} />
            <span>
              Acepto pagar <strong>{formatCop(q.overageUnitPrice)}</strong> por cada documento adicional que emita en este ciclo.
            </span>
          </label>
          <div>
            <button type="button" className="btn btn-secondary" disabled={!canDecide || !accept || !!busy} onClick={() => void acceptOverage()}>
              {busy === 'overage' ? 'Guardando…' : 'Seguir con documentos adicionales'}
            </button>
          </div>
        </div>
      )}
      {q?.overageAccepted && <Alert tone="info">Ya aceptaste documentos adicionales en este ciclo: puedes seguir emitiendo.</Alert>}
    </section>
  );
}

/** Se acabó la cuota: lo que se estaba haciendo no se pierde; se ofrece mejorar el plan. */
export function QuotaBlocked({ canSeePlans, onResolved }: { canSeePlans: boolean; onResolved: () => void }) {
  return canSeePlans ? (
    <UpgradeOffer onResolved={onResolved} />
  ) : (
    <Alert tone="warn">Se acabaron los documentos del plan. Pídele al dueño de la cuenta que elija un plan para seguir emitiendo.</Alert>
  );
}

/** Planes a ofrecer: los de la misma familia con más documentos; desde la prueba, todos los pagos. */
export function upgradeOptions(plans: Plan[], current?: Plan): Plan[] {
  const paid = plans.filter((p) => p.family !== 'TRIAL');
  if (!current || current.family === 'TRIAL') return paid;
  const docs = (p: Plan) => p.quotaDocuments ?? Number.POSITIVE_INFINITY;
  return paid.filter((p) => p.family === current.family && docs(p) > docs(current));
}

/** "· 2 meses gratis" cuando el anual equivale a menos de 12 mensualidades. */
export function annualSaving(p: Plan): string {
  // Solo frente a un pago mensual que de verdad se vende; si no, no hay ahorro que prometer.
  if (!p.monthlyBillingAllowed || !p.monthlyPrice || !p.annualPrice) return '';
  const free = 12 - Math.round(Number(p.annualPrice) / Number(p.monthlyPrice));
  return free > 0 ? ` · ${free} ${free === 1 ? 'mes' : 'meses'} gratis` : '';
}
