import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { http } from '../api/client';
import type { Invoice } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, EnvironmentBadge, Loading, PageHead, Spinner, StatusBadge } from '../components/ui';
import { isQuotaError, messageFor } from '../lib/errors';
import { QuotaBlocked } from '../components/UpgradeOffer';
import { documentTitle, formatCop, formatDate, formatDateTime, formatIdentification, isTerminal, STATUS_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';

const POLL_MS = 2500;

export function InvoiceDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { can } = useSession();
  const invoice = useApi<Invoice>(`/api/v1/invoices/${id}`);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [quotaBlocked, setQuotaBlocked] = useState(false);
  const inv = invoice.data;

  // Mientras está en camino a la DIAN, se consulta sola hasta tener respuesta final.
  const inFlight = !!inv && inv.status !== 'DRAFT' && !isTerminal(inv.status);
  useEffect(() => {
    if (!inFlight) return;
    const t = setInterval(() => void invoice.reload(), POLL_MS);
    return () => clearInterval(t);
  }, [inFlight, invoice.reload]);

  if (invoice.loading && !inv) return <Loading />;
  if (invoice.error && !inv) return <Alert tone="error">{messageFor(invoice.error)}</Alert>;
  if (!inv) return null;

  async function emit() {
    if (!window.confirm('Al emitir se asigna el número y la factura se envía. Después no se puede modificar. ¿Emitir?')) return;
    setBusy(true);
    setError(null);
    try {
      await http.post<Invoice>(`/api/v1/invoices/${id}/finalize`);
      await invoice.reload();
    } catch (err) {
      if (isQuotaError(err)) setQuotaBlocked(true);
      else setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!window.confirm('¿Borrar este borrador?')) return;
    try {
      await http.del(`/api/v1/invoices/${id}`);
      navigate('/facturas', { replace: true });
    } catch (err) {
      setError(messageFor(err));
    }
  }

  const isDraft = inv.status === 'DRAFT';

  return (
    <>
      <PageHead
        title={documentTitle(inv.kind, inv.number)}
        subtitle={`${inv.customer.legalName} · ${formatDate(inv.issueDate)}`}
        action={
          <div className="row">
            <StatusBadge status={inv.status} />
            <EnvironmentBadge environment={inv.environment} />
          </div>
        }
      />

      {error && <Alert tone="error">{error}</Alert>}
      {quotaBlocked && <QuotaBlocked canSeePlans={can('usage')} onResolved={() => setQuotaBlocked(false)} />}
      <StatusExplanation invoice={inv} />

      {isDraft && can('write') && (
        <div className="row">
          {can('finalize') && (
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void emit()}>
              {busy ? 'Emitiendo…' : 'Emitir factura'}
            </button>
          )}
          <Link to={`/facturas/${inv.id}/editar`} className="btn btn-secondary">
            Editar
          </Link>
          <button type="button" className="btn btn-danger right" onClick={() => void remove()}>
            Borrar borrador
          </button>
        </div>
      )}

      <section className="card stack" aria-labelledby="cliente-h">
        <h2 id="cliente-h">Cliente</h2>
        <p>
          <strong>{inv.customer.legalName}</strong>
          <br />
          <span className="muted">
            {formatIdentification(inv.customer.identificationType, inv.customer.identificationNumber, inv.customer.checkDigit)}
            {inv.customer.email ? ` · ${inv.customer.email}` : ''}
          </span>
        </p>
      </section>

      <section className="card stack" aria-labelledby="detalle-h">
        <h2 id="detalle-h">Detalle</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Descripción</th>
                <th className="num">Cant.</th>
                <th className="num">Precio</th>
                <th className="num">Descuento</th>
                <th className="num">Impuestos</th>
                <th className="num">Total</th>
              </tr>
            </thead>
            <tbody>
              {inv.lines.map((l) => (
                <tr key={l.lineNumber}>
                  <td>{l.description}</td>
                  <td className="num">{l.quantity}</td>
                  <td className="num">{formatCop(l.unitPrice)}</td>
                  <td className="num">{l.discountAmount ? `−${formatCop(l.discountAmount)}` : '—'}</td>
                  <td className="num">{formatCop(l.taxTotal)}</td>
                  <td className="num">{formatCop(l.lineTotal)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className="totals">
          <dt>Subtotal</dt>
          <dd className="num">{formatCop(inv.totals.lineExtensionAmount)}</dd>
          {inv.taxSubtotals.map((t) => (
            <FragmentRow key={`${t.code}`} label={`${t.type} ${t.percentage} % sobre ${formatCop(t.taxableAmount)}`} value={formatCop(t.taxAmount)} />
          ))}
          <dt className="grand">Total</dt>
          <dd className="grand num">{formatCop(inv.totals.payableAmount)}</dd>
        </dl>
      </section>

      {inv.documentKey && (
        <section className="card stack" aria-labelledby="evidencia-h">
          <h2 id="evidencia-h">Evidencia del proveedor</h2>
          <p className="small muted">{inv.simulated ? 'Clave simulada: no es un código DIAN real.' : inv.kind === 'POS_EQUIVALENT' ? 'CUDE' : 'CUFE'}</p>
          <p className="mono">{inv.documentKey}</p>
          <p className="small muted">Validada: {formatDateTime(inv.validatedAt)}</p>
        </section>
      )}

      {inv.history.length > 0 && (
        <section className="card stack" aria-labelledby="historial-h">
          <h2 id="historial-h">Historial</h2>
          <ol className="timeline">
            {inv.history.map((h, i) => (
              <li key={i}>
                <time dateTime={h.at}>{formatDateTime(h.at)}</time>
                <span>{STATUS_LABEL[h.to]}</span>
              </li>
            ))}
          </ol>
        </section>
      )}
    </>
  );
}

function FragmentRow({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="muted">{label}</dt>
      <dd className="num">{value}</dd>
    </>
  );
}

function StatusExplanation({ invoice: inv }: { invoice: Invoice }) {
  switch (inv.status) {
    case 'DRAFT':
      return <Alert tone="info">Es un borrador: revisa los totales. Al emitirla se le asigna el número de tu resolución.</Alert>;
    case 'PENDING_SUBMISSION':
    case 'SUBMITTED':
      return (
        <Alert tone="info">
          <span className="row">
            <Spinner label="Esperando respuesta" />
            {inv.outcomeUncertain
              ? 'Estamos confirmando con el proveedor si la recibió. No la reenviaremos a ciegas, así no habrá duplicados.'
              : 'Enviando a la DIAN por medio del proveedor. Esto suele tardar unos segundos.'}
          </span>
        </Alert>
      );
    case 'ACCEPTED':
      return inv.legallyValid ? (
        <Alert tone="ok">Factura aceptada. Es un documento válido ante la DIAN.</Alert>
      ) : (
        <Alert tone="warn">
          Aceptada en <strong>{inv.simulated ? 'simulación' : 'habilitación'}</strong>: el flujo funcionó, pero este documento no tiene validez tributaria.
        </Alert>
      );
    case 'REJECTED':
      return (
        <Alert tone="error">
          <strong>La DIAN o el proveedor rechazó la factura.</strong>
          {inv.rejectionReasons.length > 0 && (
            <ul>
              {inv.rejectionReasons.map((r, i) => (
                <li key={i}>{r}</li>
              ))}
            </ul>
          )}
        </Alert>
      );
    case 'FAILED':
      return <Alert tone="error">No fue posible enviarla tras varios intentos. Nuestro equipo debe revisarla; escríbenos a soporte.</Alert>;
  }
}
