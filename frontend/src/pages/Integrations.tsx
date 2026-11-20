import { useState, type FormEvent } from 'react';
import { http } from '../api/client';
import type { ApiKeyInfo, WebhookDeliveryInfo, WebhookEndpointInfo } from '../api/types';
import { Alert, Field, PageHead } from '../components/ui';
import { messageFor } from '../lib/errors';
import { formatDateTime } from '../lib/format';
import { useApi } from '../lib/useApi';

const SCOPES: { code: string; label: string }[] = [
  { code: 'INVOICE_READ', label: 'Ver documentos' },
  { code: 'INVOICE_WRITE', label: 'Crear borradores' },
  { code: 'INVOICE_FINALIZE', label: 'Emitir documentos' },
  { code: 'CUSTOMER_READ', label: 'Ver clientes' },
  { code: 'CUSTOMER_WRITE', label: 'Crear y editar clientes' },
  { code: 'PRODUCT_READ', label: 'Ver productos' },
  { code: 'PRODUCT_WRITE', label: 'Crear y editar productos' },
];

const EVENTS: { code: string; label: string }[] = [
  { code: 'document.accepted', label: 'Documento validado' },
  { code: 'document.rejected', label: 'Documento rechazado' },
  { code: 'document.failed', label: 'Envío fallido' },
];

/** Conectar otras apps (AdoptaFácil, una tienda en línea): claves de API y avisos firmados. */
export function Integrations() {
  return (
    <>
      <PageHead
        title="Integraciones"
        subtitle="Conecta otras apps para que facturen por ti y reciban el resultado. La documentación de la API está en /swagger-ui.html."
      />
      <ApiKeys />
      <Webhooks />
    </>
  );
}

function Secret({ label, value, note }: { label: string; value: string; note: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="secret-box stack tight-stack" role="status">
      <strong>{label}</strong>
      <code className="mono">{value}</code>
      <div className="row">
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => void navigator.clipboard.writeText(value).then(() => setCopied(true)).catch(() => undefined)}
        >
          {copied ? 'Copiado' : 'Copiar'}
        </button>
        <span className="small muted">{note}</span>
      </div>
    </div>
  );
}

function ApiKeys() {
  const keys = useApi<ApiKeyInfo[]>('/api/v1/api-keys');
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['INVOICE_READ', 'INVOICE_WRITE', 'INVOICE_FINALIZE', 'CUSTOMER_READ', 'CUSTOMER_WRITE']);
  const [issued, setIssued] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const r = await http.post<{ key: string }>('/api/v1/api-keys', { name: name.trim(), scopes });
      setIssued(r.key);
      setName('');
      await keys.reload();
    } catch (err) {
      setError(messageFor(err));
    }
  }

  async function revoke(id: string) {
    if (!window.confirm('La app que use esta clave dejará de funcionar de inmediato. ¿Revocar?')) return;
    try {
      await http.del(`/api/v1/api-keys/${id}`);
      await keys.reload();
    } catch (err) {
      setError(messageFor(err));
    }
  }

  return (
    <section className="card stack" aria-labelledby="keys-h">
      <h2 id="keys-h">Claves de API</h2>
      <p className="small muted">
        La app las envía como <code>Authorization: Bearer bpk_…</code>. Cada clave tiene solo los permisos que marques y nunca puede administrar
        usuarios, planes ni otras claves.
      </p>
      {error && <Alert tone="error">{error}</Alert>}
      {issued && <Secret label="Tu nueva clave" value={issued} note="Guárdala ahora en un lugar seguro: no se volverá a mostrar." />}
      <form className="stack" onSubmit={(e) => void create(e)}>
        <Field label="Nombre de la clave" value={name} onChange={(e) => setName(e.target.value)} placeholder="AdoptaFácil marketplace" />
        <fieldset className="fieldset">
          <legend>Permisos</legend>
          <div className="check-list">
            {SCOPES.map((s) => (
              <label key={s.code} className="consent">
                <input
                  type="checkbox"
                  checked={scopes.includes(s.code)}
                  onChange={() => setScopes((cur) => (cur.includes(s.code) ? cur.filter((x) => x !== s.code) : [...cur, s.code]))}
                />
                <span>{s.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <div>
          <button type="submit" className="btn btn-primary" disabled={!name.trim() || scopes.length === 0}>
            Crear clave
          </button>
        </div>
      </form>
      {(keys.data ?? []).length > 0 && (
        <ul className="plain-list">
          {keys.data!.map((k) => (
            <li key={k.id} className="key-row">
              <div className="grow">
                <strong>{k.name}</strong> <code className="small">bpk_{k.prefix}_…</code>
                <div className="small muted">
                  {k.revokedAt ? `Revocada ${formatDateTime(k.revokedAt)}` : k.lastUsedAt ? `Último uso ${formatDateTime(k.lastUsedAt)}` : 'Sin usar todavía'}
                  {' · '}
                  {k.scopes.map((s) => SCOPES.find((x) => x.code === s)?.label ?? s).join(', ')}
                </div>
              </div>
              {!k.revokedAt && (
                <button type="button" className="btn btn-ghost btn-sm danger-text" onClick={() => void revoke(k.id)}>
                  Revocar
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Webhooks() {
  const endpoints = useApi<WebhookEndpointInfo[]>('/api/v1/webhook-endpoints');
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState<string[]>(['document.accepted', 'document.rejected']);
  const [secret, setSecret] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const r = await http.post<{ secret: string }>('/api/v1/webhook-endpoints', { url: url.trim(), events });
      setSecret(r.secret);
      setUrl('');
      await endpoints.reload();
    } catch (err) {
      setError(messageFor(err));
    }
  }

  return (
    <section className="card stack" aria-labelledby="hooks-h">
      <h2 id="hooks-h">Avisos a otras apps (webhooks)</h2>
      <p className="small muted">
        Cuando un documento se valida, se rechaza o falla, enviamos un POST firmado a tu URL. Verifica la cabecera{' '}
        <code>X-Billing-Signature: t=…,v1=…</code> con HMAC-SHA256 de <code>t.cuerpo</code> y tu secreto, y rechaza firmas de más de 5 minutos.
        Si tu app no responde 2xx, reintentamos hasta 8 veces durante unas 48 horas.
      </p>
      {error && <Alert tone="error">{error}</Alert>}
      {secret && <Secret label="Secreto de firma" value={secret} note="Guárdalo en tu app: no se volverá a mostrar." />}
      <form className="stack" onSubmit={(e) => void create(e)}>
        <Field label="URL que recibe los avisos" type="url" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://tu-app.co/webhooks/facturacion" hint="Solo HTTPS y un dominio público." />
        <fieldset className="fieldset">
          <legend>Eventos</legend>
          <div className="check-list">
            {EVENTS.map((ev) => (
              <label key={ev.code} className="consent">
                <input
                  type="checkbox"
                  checked={events.includes(ev.code)}
                  onChange={() => setEvents((cur) => (cur.includes(ev.code) ? cur.filter((x) => x !== ev.code) : [...cur, ev.code]))}
                />
                <span>
                  {ev.label} <code className="small">{ev.code}</code>
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        <div>
          <button type="submit" className="btn btn-primary" disabled={!url.trim() || events.length === 0}>
            Registrar URL
          </button>
        </div>
      </form>
      {(endpoints.data ?? []).filter((e) => e.active).map((e) => (
        <div key={e.id} className="key-row stack tight-stack">
          <div className="row">
            <div className="grow">
              <strong className="mono small">{e.url}</strong>
              <div className="small muted">{e.events.join(', ')}</div>
            </div>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void http.post(`/api/v1/webhook-endpoints/${e.id}/test`).then(() => setOpen(e.id))}>
              Enviar prueba
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(open === e.id ? null : e.id)}>
              Entregas
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm danger-text"
              onClick={() => {
                if (window.confirm('¿Dejar de enviar avisos a esta URL?')) void http.del(`/api/v1/webhook-endpoints/${e.id}`).then(() => endpoints.reload());
              }}
            >
              Desactivar
            </button>
          </div>
          {open === e.id && <Deliveries endpointId={e.id} />}
        </div>
      ))}
    </section>
  );
}

function Deliveries({ endpointId }: { endpointId: string }) {
  const list = useApi<WebhookDeliveryInfo[]>(`/api/v1/webhook-endpoints/${endpointId}/deliveries`);
  const label = { PENDING: 'Pendiente', DELIVERED: 'Entregado', FAILED: 'Fallido' } as const;
  if (!list.data) return null;
  if (list.data.length === 0) return <p className="small muted">Aún no hay entregas.</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Evento</th>
            <th>Estado</th>
            <th className="num">Intentos</th>
            <th>Detalle</th>
            <th>Creado</th>
          </tr>
        </thead>
        <tbody>
          {list.data.map((d) => (
            <tr key={d.id}>
              <td className="mono small">{d.event}</td>
              <td>{label[d.status]}</td>
              <td className="num">{d.attempts}</td>
              <td className="small">{d.lastError ?? (d.lastStatus ? `HTTP ${d.lastStatus}` : '—')}</td>
              <td className="small">{formatDateTime(d.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
