import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { http } from '../api/client';
import type { Invoice } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, EnvironmentBadge, Field, Loading, PageHead, Spinner, StatusBadge } from '../components/ui';
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
      {inv.status === 'ACCEPTED' && <ShareActions invoiceId={inv.id} customerEmail={inv.customer.email} canSend={can('write')} />}

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

/** Ver, imprimir y hacer llegar el documento al comprador: correo, WhatsApp o enlace copiado. */
function ShareActions({ invoiceId, customerEmail, canSend }: { invoiceId: string; customerEmail?: string; canSend: boolean }) {
  const [email, setEmail] = useState(customerEmail ?? '');
  const [busy, setBusy] = useState<'mail' | 'link' | null>(null);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
  const [link, setLink] = useState<string | null>(null);

  async function send(e: FormEvent) {
    e.preventDefault();
    setBusy('mail');
    setNotice(null);
    try {
      const r = await http.post<{ sentTo: string }>(`/api/v1/invoices/${invoiceId}/send`, { email: email.trim() || undefined });
      setNotice({ tone: 'ok', text: `Enviado a ${r.sentTo}.` });
    } catch (err) {
      setNotice({ tone: 'error', text: messageFor(err) });
    } finally {
      setBusy(null);
    }
  }

  async function createLink(): Promise<string | null> {
    if (link) return link;
    setBusy('link');
    setNotice(null);
    try {
      const r = await http.post<{ link: string }>(`/api/v1/invoices/${invoiceId}/share-links`);
      setLink(r.link);
      return r.link;
    } catch (err) {
      setNotice({ tone: 'error', text: messageFor(err) });
      return null;
    } finally {
      setBusy(null);
    }
  }

  async function copy() {
    const url = await createLink();
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setNotice({ tone: 'ok', text: 'Enlace copiado. Pégalo donde quieras.' });
    } catch {
      setNotice({ tone: 'ok', text: 'Enlace listo: cópialo de la casilla de abajo.' });
    }
  }

  async function whatsapp() {
    // La pestaña se abre antes de esperar al servidor: los navegadores bloquean ventanas abiertas después.
    const tab = window.open('', '_blank');
    if (tab) tab.opener = null;
    const url = await createLink();
    if (!url) {
      tab?.close();
      return;
    }
    const target = `https://wa.me/?text=${encodeURIComponent(`Hola, aquí está tu documento: ${url}`)}`;
    if (tab) tab.location.href = target;
    else window.location.href = target;
  }

  return (
    <section className="card stack" aria-labelledby="enviar-h">
      <div className="section-title">
        <h2 id="enviar-h">Entregar al cliente</h2>
        <Link to={`/facturas/${invoiceId}/documento`} className="btn btn-secondary">
          Ver e imprimir
        </Link>
      </div>
      {notice && <Alert tone={notice.tone}>{notice.text}</Alert>}
      {canSend ? (
        <>
          <form className="row share-row" onSubmit={(e) => void send(e)}>
            <Field label="Enviar al correo" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="cliente@correo.com" />
            <button type="submit" className="btn btn-primary" disabled={busy !== null}>
              {busy === 'mail' ? 'Enviando…' : 'Enviar'}
            </button>
          </form>
          <div className="row">
            <button type="button" className="btn btn-secondary" disabled={busy !== null} onClick={() => void whatsapp()}>
              Enviar por WhatsApp
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy !== null} onClick={() => void copy()}>
              Copiar enlace
            </button>
          </div>
          {link && <input className="mono small" readOnly value={link} aria-label="Enlace del documento" onFocus={(e) => e.target.select()} />}
          <p className="small muted">El enlace deja ver e imprimir el documento sin cuenta y vence en 180 días.</p>
        </>
      ) : (
        <p className="small muted">Pide a quien factura que lo envíe.</p>
      )}
    </section>
  );
}
