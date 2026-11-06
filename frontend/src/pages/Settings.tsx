import { useState, type FormEvent } from 'react';
import { http } from '../api/client';
import type { DocumentKind, Environment, NumberingRange, PaymentAccount, ProviderAccount } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, EnvironmentBadge, Field, PageHead, SelectField, Spinner } from '../components/ui';
import { messageFor } from '../lib/errors';
import { ENVIRONMENT_LABEL, formatDate, KIND_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';

export function Settings() {
  const { can, logout } = useSession();
  const accounts = useApi<ProviderAccount[]>('/api/v1/provider-accounts');
  const ranges = useApi<NumberingRange[]>('/api/v1/numbering-ranges');
  const active = accounts.data?.find((a) => a.active);

  return (
    <>
      <PageHead title="Ajustes" subtitle="Cómo se envían tus facturas y con qué numeración." />
      <ProviderSection accounts={accounts.data} loading={accounts.loading} canManage={can('provider')} onChange={accounts.reload} />
      <RangesSection ranges={ranges.data} loading={ranges.loading} canManage={can('manage')} activeEnvironment={active?.environment} onChange={ranges.reload} />
      <PaymentSection canManage={can('provider')} />
      <section className="card stack">
        <h2>Tu sesión</h2>
        <div>
          <button type="button" className="btn btn-secondary" onClick={() => void logout()}>
            Cerrar sesión
          </button>
        </div>
      </section>
    </>
  );
}

function ProviderSection(props: { accounts: ProviderAccount[] | null; loading: boolean; canManage: boolean; onChange: () => Promise<void> }) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const active = props.accounts?.find((a) => a.active);

  async function activateSimulation() {
    setBusy(true);
    setError(null);
    try {
      await http.post('/api/v1/provider-accounts', { providerId: 'mock', environment: 'SIMULATION' });
      await props.onChange();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack" aria-labelledby="prov-h">
      <h2 id="prov-h">Envío a la DIAN</h2>
      {error && <Alert tone="error">{error}</Alert>}
      {props.loading ? (
        <Spinner />
      ) : active ? (
        <div className="row">
          <span>
            Activo: <strong>{active.simulated ? 'Simulación' : active.providerId}</strong> · {ENVIRONMENT_LABEL[active.environment]}
          </span>
          <EnvironmentBadge environment={active.environment} />
        </div>
      ) : (
        <p className="muted">Aún no has elegido cómo se envían tus facturas.</p>
      )}
      <Alert tone="info">
        <strong>Simulación</strong> te deja practicar todo el flujo sin enviar nada a la DIAN. El envío real (habilitación y producción) se activa
        cuando tu empresa esté habilitada como facturador electrónico y conectemos tu cuenta con el proveedor autorizado.
      </Alert>
      {props.canManage && !active?.simulated && (
        <div>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void activateSimulation()}>
            {busy ? 'Activando…' : 'Usar modo simulación'}
          </button>
        </div>
      )}
      {!props.canManage && <p className="small muted">Solo el dueño de la cuenta puede cambiar esto.</p>}
    </section>
  );
}

function RangesSection(props: {
  ranges: NumberingRange[] | null;
  loading: boolean;
  canManage: boolean;
  activeEnvironment?: Environment;
  onChange: () => Promise<void>;
}) {
  const today = localToday();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({
    kind: 'INVOICE' as DocumentKind,
    environment: (props.activeEnvironment ?? 'SIMULATION') as Environment,
    resolutionNumber: '',
    prefix: '',
    rangeFrom: '1',
    rangeTo: '',
    validFrom: today,
    validTo: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await http.post('/api/v1/numbering-ranges', {
        kind: form.kind,
        environment: form.environment,
        resolutionNumber: form.resolutionNumber.trim(),
        prefix: form.prefix.trim(),
        rangeFrom: Number(form.rangeFrom),
        rangeTo: Number(form.rangeTo),
        validFrom: form.validFrom,
        validTo: form.validTo,
      });
      setOpen(false);
      await props.onChange();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack" aria-labelledby="num-h">
      <div className="row">
        <h2 id="num-h">Resoluciones de numeración</h2>
        {props.canManage && !open && (
          <button type="button" className="btn btn-secondary right" onClick={() => setOpen(true)}>
            Registrar resolución
          </button>
        )}
      </div>
      {props.loading ? (
        <Spinner />
      ) : props.ranges && props.ranges.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Tipo</th>
                <th>Ambiente</th>
                <th>Resolución</th>
                <th>Prefijo</th>
                <th className="num">Rango</th>
                <th className="num">Disponibles</th>
                <th>Vence</th>
              </tr>
            </thead>
            <tbody>
              {props.ranges.map((r) => (
                <tr key={r.id}>
                  <td>{KIND_LABEL[r.kind]}</td>
                  <td>{ENVIRONMENT_LABEL[r.environment]}</td>
                  <td>{r.resolutionNumber}</td>
                  <td>{r.prefix || '—'}</td>
                  <td className="num">
                    {r.rangeFrom}–{r.rangeTo}
                  </td>
                  <td className="num">{r.remaining}</td>
                  <td>{formatDate(r.validTo)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted">Aún no hay resoluciones registradas.</p>
      )}

      {open && (
        <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
          <Alert tone="info">Copia los datos tal como aparecen en la resolución que te dio la DIAN. Para practicar en simulación puedes inventar un número.</Alert>
          {error && <Alert tone="error">{error}</Alert>}
          <SelectField
            label="Tipo de documento"
            value={form.kind}
            onChange={(e) => setForm({ ...form, kind: e.target.value as DocumentKind })}
            hint="La DIAN da una resolución para facturas y otra para el POS de tu caja."
          >
            <option value="INVOICE">Factura de venta</option>
            <option value="POS_EQUIVALENT">POS (tiquete de venta electrónico)</option>
          </SelectField>
          <div className="grid-2">
            <SelectField label="Ambiente" value={form.environment} onChange={(e) => setForm({ ...form, environment: e.target.value as Environment })}>
              <option value="SIMULATION">Simulación</option>
              <option value="HABILITATION">Habilitación DIAN</option>
              <option value="PRODUCTION">Producción</option>
            </SelectField>
            <Field label="Número de resolución" required value={form.resolutionNumber} onChange={(e) => setForm({ ...form, resolutionNumber: e.target.value })} />
          </div>
          <div className="grid-2">
            <Field label="Prefijo" hint="Ejemplo: FEV. Puede estar vacío." maxLength={10} value={form.prefix} onChange={(e) => setForm({ ...form, prefix: e.target.value })} />
            <div className="grid-2">
              <Field label="Desde" inputMode="numeric" value={form.rangeFrom} onChange={(e) => setForm({ ...form, rangeFrom: e.target.value.replace(/\D/g, '') })} />
              <Field label="Hasta" inputMode="numeric" value={form.rangeTo} onChange={(e) => setForm({ ...form, rangeTo: e.target.value.replace(/\D/g, '') })} />
            </div>
          </div>
          <div className="grid-2">
            <Field label="Vigente desde" type="date" value={form.validFrom} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} />
            <Field label="Vigente hasta" type="date" value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} />
          </div>
          <div className="row">
            <button type="submit" className="btn btn-primary" disabled={busy || !form.resolutionNumber || !form.rangeTo || !form.validTo}>
              {busy ? 'Guardando…' : 'Guardar resolución'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={() => setOpen(false)}>
              Cancelar
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

/**
 * Cobros con la cuenta Bold DEL COMERCIO: el dinero llega directo a su cuenta; la plataforma
 * solo crea el cobro y emite el documento cuando Bold confirma el pago.
 */
function PaymentSection({ canManage }: { canManage: boolean }) {
  const account = useApi<PaymentAccount>('/api/v1/payment-accounts');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ mode: 'TEST' as 'TEST' | 'LIVE', identityKey: '', secretKey: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState<PaymentAccount | null>(null);
  const current = account.data;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await http.post<PaymentAccount>('/api/v1/payment-accounts', {
        mode: form.mode,
        identityKey: form.identityKey.trim(),
        secretKey: form.secretKey.trim() || undefined,
      });
      setConnected(result);
      setForm({ mode: form.mode, identityKey: '', secretKey: '' }); // las llaves no se quedan en pantalla
      setOpen(false);
      await account.reload();
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  const webhookUrl = connected?.webhookPath ? `${window.location.origin}${connected.webhookPath}` : null;

  return (
    <section className="card stack" aria-labelledby="pay-h">
      <div className="row">
        <h2 id="pay-h">Cobros con QR (Bold)</h2>
        {canManage && !open && (
          <button type="button" className="btn btn-secondary right" onClick={() => setOpen(true)}>
            {current?.connected ? 'Cambiar llaves' : 'Conectar Bold'}
          </button>
        )}
      </div>
      {account.loading ? (
        <Spinner />
      ) : current?.connected ? (
        <p>
          Conectado: <strong>Bold</strong> · {current.mode === 'LIVE' ? 'cobros reales' : 'modo de prueba (no mueve dinero)'}
        </p>
      ) : (
        <p className="muted">
          Conecta tu cuenta Bold y cobra con QR (Bre-B, Nequi, tarjetas, PSE según lo que tengas activo). El dinero llega a tu cuenta Bold; cuando el
          pago se confirma, el POS electrónico sale solo.
        </p>
      )}

      {webhookUrl && (
        <Alert tone="ok">
          <strong>Listo.</strong> Medios activos en tu Bold: {connected?.paymentMethods?.join(', ') || 'sin datos'}.
          <br />
          Último paso: agrega esta URL en la sección de webhooks de tu panel de Bold. <strong>Solo se muestra ahora</strong>; si la pierdes,
          vuelve a conectar.
          <span className="mono secret-once">{webhookUrl}</span>
        </Alert>
      )}

      {open && (
        <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
          <Alert tone="info">
            En tu panel de Bold ve a <strong>Integraciones → Llaves de integración → Botón de pagos</strong> y copia las llaves. Empieza con las de
            prueba. Se guardan cifradas y nunca se vuelven a mostrar.
          </Alert>
          {error && <Alert tone="error">{error}</Alert>}
          <SelectField label="Tipo de llaves" value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as 'TEST' | 'LIVE' })}
            hint="Las de prueba funcionan con documentos en simulación o habilitación; las reales, solo con documentos en producción.">
            <option value="TEST">Prueba (no mueve dinero)</option>
            <option value="LIVE">Producción (cobros reales)</option>
          </SelectField>
          <Field label="Llave de identidad" required autoComplete="off" spellCheck={false} value={form.identityKey}
            onChange={(e) => setForm({ ...form, identityKey: e.target.value })} />
          <Field label="Llave secreta" type="password" autoComplete="new-password" spellCheck={false} value={form.secretKey}
            hint={form.mode === 'LIVE' ? 'Obligatoria para cobros reales: con ella verificamos que cada aviso viene de Bold.' : 'En prueba puede ir vacía.'}
            onChange={(e) => setForm({ ...form, secretKey: e.target.value })} />
          <div className="row">
            <button type="submit" className="btn btn-primary" disabled={busy || !form.identityKey.trim() || (form.mode === 'LIVE' && !form.secretKey.trim())}>
              {busy ? 'Verificando con Bold…' : 'Conectar'}
            </button>
            <button type="button" className="btn btn-secondary" onClick={() => setOpen(false)}>
              Cancelar
            </button>
          </div>
        </form>
      )}
      {!canManage && <p className="small muted">Solo el dueño de la cuenta puede conectar la pasarela.</p>}
    </section>
  );
}

/** Fecha de hoy en la zona del navegador (no en UTC: a las 8 p. m. en Colombia ya es mañana en UTC). */
function localToday(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
