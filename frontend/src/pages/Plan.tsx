import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { http } from '../api/client';
import type { Plan as PlanData, Quota, SubscriptionCheckout, SubscriptionInfo } from '../api/types';
import { useSession } from '../auth/session';
import { UpgradeOffer } from '../components/UpgradeOffer';
import { Alert, Loading, PageHead, Spinner } from '../components/ui';
import { formatCop, formatDate } from '../lib/format';
import { useApi } from '../lib/useApi';

export function Plan() {
  const { can } = useSession();
  const quota = useApi<Quota>(can('usage') ? '/api/v1/subscription/quota' : null);
  const plans = useApi<PlanData[]>('/api/v1/public/plans');
  const subscription = useApi<SubscriptionInfo>(can('usage') ? '/api/v1/subscription' : null);
  const [params, setParams] = useSearchParams();
  const returning = params.get('pago');

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
  const reload = () => {
    void quota.reload();
    void subscription.reload();
  };
  const sub = subscription.data;

  return (
    <>
      <PageHead title="Mi plan" subtitle="Lo que tienes contratado, cuánto llevas y cómo cambiarlo." />

      {returning && (
        <PaymentReturn checkoutId={returning} onDone={() => { reload(); }} onDismiss={() => setParams({}, { replace: true })} />
      )}
      {sub?.status === 'PAST_DUE' && (
        <Alert tone="error">
          Tu plan venció el {formatDate(sub.periodEnd)}. Mientras no se pague no se pueden emitir documentos; tus datos siguen intactos.
        </Alert>
      )}

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
            {sub && sub.status === 'ACTIVE' && <p className="small muted">Pagado hasta el {formatDate(sub.periodEnd)}.</p>}
          </div>
        )}
      </section>

      {q?.overageAccepted && (
        <Alert tone="info">
          Este mes aceptaste documentos adicionales a {formatCop(q.overageUnitPrice)} cada uno, después de los {limit} del plan.
        </Alert>
      )}
      <UpgradeOffer showAll includeCurrent={!isTrial} title={isTrial ? 'Elige tu plan' : 'Renovar o cambiar de plan'} onResolved={reload} />

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

const POLL_MS = 2500;
const POLL_LIMIT = 24; // ~1 minuto; después sigue la conciliación automática

/** Al volver de Mercado Pago: se consulta el pago hasta saber si quedó aprobado. */
function PaymentReturn({ checkoutId, onDone, onDismiss }: { checkoutId: string; onDone: () => void; onDismiss: () => void }) {
  const [checkout, setCheckout] = useState<SubscriptionCheckout | null>(null);
  const [tries, setTries] = useState(0);
  const [failed, setFailed] = useState(false);
  const notified = useRef(false);
  const valid = /^[0-9a-f-]{36}$/i.test(checkoutId);

  useEffect(() => {
    if (!valid) return;
    if (checkout && (checkout.status !== 'PENDING' || checkout.lastAttempt === 'REJECTED' || tries >= POLL_LIMIT)) {
      if (checkout.status === 'PAID' && !notified.current) {
        notified.current = true; // una sola recarga del plan
        onDone();
      }
      return;
    }
    const t = window.setTimeout(() => {
      http.get<SubscriptionCheckout>(`/api/v1/subscription/checkouts/${checkoutId}`)
        .then((c) => {
          setCheckout(c);
          setTries((n) => n + 1);
        })
        .catch(() => setFailed(true));
    }, checkout ? POLL_MS : 0);
    return () => window.clearTimeout(t);
  }, [checkout, tries, checkoutId, valid, onDone]);

  if (!valid || failed) return null;
  if (!checkout || (checkout.status === 'PENDING' && checkout.lastAttempt !== 'REJECTED' && tries < POLL_LIMIT)) {
    return (
      <Alert tone="info">
        <span className="row">
          <Spinner label="Confirmando el pago" />
          Confirmando tu pago con Mercado Pago…
        </span>
      </Alert>
    );
  }
  if (checkout.status === 'PAID') {
    return (
      <Alert tone="ok">
        <strong>Pago recibido.</strong> Tu plan ya está activo. Gracias por apoyar también el fondo solidario.{' '}
        <button type="button" className="btn btn-ghost" onClick={onDismiss}>
          Cerrar
        </button>
      </Alert>
    );
  }
  if (checkout.lastAttempt === 'REJECTED') {
    return (
      <Alert tone="error">
        Mercado Pago rechazó el pago. Intenta con otro medio de pago eligiendo el plan otra vez.{' '}
        <button type="button" className="btn btn-ghost" onClick={onDismiss}>
          Cerrar
        </button>
      </Alert>
    );
  }
  return (
    <Alert tone="warn">
      Tu pago aún está en proceso (algunos medios, como PSE o efectivo, tardan en confirmarse). Activaremos el plan apenas Mercado Pago lo
      confirme; puedes cerrar esta página.{' '}
      <button type="button" className="btn btn-ghost" onClick={onDismiss}>
        Entendido
      </button>
    </Alert>
  );
}
