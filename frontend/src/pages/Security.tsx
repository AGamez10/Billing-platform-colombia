import { useState } from 'react';
import { http } from '../api/client';
import { BRAND_NAME } from '../brand';
import { QrCode } from '../components/QrCode';
import { Alert, Field, PageHead, Spinner } from '../components/ui';
import { messageFor } from '../lib/errors';
import { useApi } from '../lib/useApi';

interface Status {
  enabled: boolean;
  recoveryCodesLeft: number;
}

interface Setup {
  secret: string;
  otpauthUri: string;
}

/** Verificación en dos pasos de la persona (vale en todas sus organizaciones). */
export function Security() {
  const status = useApi<Status>('/api/v1/account/two-factor');
  const [codes, setCodes] = useState<string[] | null>(null);

  return (
    <>
      <PageHead title="Seguridad de tu cuenta" subtitle="Protege el acceso a tus facturas aunque alguien conozca tu contraseña." />
      {codes ? (
        <RecoveryCodesCard codes={codes} onDone={() => { setCodes(null); void status.reload(); }} />
      ) : status.loading && !status.data ? (
        <Spinner />
      ) : status.data?.enabled ? (
        <EnabledCard status={status.data} onCodes={setCodes} onDisabled={() => void status.reload()} />
      ) : (
        <SetupCard onEnabled={setCodes} />
      )}
    </>
  );
}

function SetupCard({ onEnabled }: { onEnabled: (codes: string[]) => void }) {
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      setSetup(await http.post<Setup>('/api/v1/account/two-factor/setup'));
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  async function enable() {
    setBusy(true);
    setError(null);
    try {
      const res = await http.post<{ recoveryCodes: string[] }>('/api/v1/account/two-factor/enable', { code: code.trim() });
      onEnabled(res.recoveryCodes);
    } catch (err) {
      setError(messageFor(err));
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack" aria-labelledby="dos-pasos">
      <div className="row">
        <h2 id="dos-pasos">Verificación en dos pasos</h2>
        <span className="badge right">Desactivada</span>
      </div>
      {error && <Alert tone="error">{error}</Alert>}
      {!setup ? (
        <>
          <p>
            Además de tu contraseña, al entrar te pediremos un código que cambia cada 30 segundos en tu celular. Sirve con
            Google Authenticator, Microsoft Authenticator, 1Password o cualquier app de autenticación.
          </p>
          <div>
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void start()}>
              {busy ? 'Preparando…' : 'Activar verificación en dos pasos'}
            </button>
          </div>
        </>
      ) : (
        <div className="stack">
          <ol className="plain-steps">
            <li>Abre tu app de autenticación y agrega una cuenta escaneando este código.</li>
          </ol>
          <div className="qr-box">
            <QrCode value={setup.otpauthUri} label={`Código QR para agregar ${BRAND_NAME} a tu app de autenticación`} size={200} />
          </div>
          <details>
            <summary>¿No puedes escanearlo? Escribe la clave</summary>
            <p className="mono" aria-label="Clave para escribir en la app">
              {setup.secret.match(/.{1,4}/g)?.join(' ')}
            </p>
          </details>
          <ol className="plain-steps" start={2}>
            <li>Escribe el código de 6 dígitos que muestra la app para confirmar.</li>
          </ol>
          <Field label="Código de 6 dígitos" inputMode="numeric" autoComplete="one-time-code" maxLength={7} value={code}
            onChange={(e) => setCode(e.target.value)} />
          <div className="row">
            <button type="button" className="btn btn-primary" disabled={busy || !/^\d{6}$/.test(code.replace(/\s/g, ''))}
              onClick={() => void enable()}>
              {busy ? 'Confirmando…' : 'Confirmar y activar'}
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => setSetup(null)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function RecoveryCodesCard({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const text = `Códigos de recuperación de ${BRAND_NAME}\nCada uno sirve una sola vez si pierdes el teléfono.\n\n${codes.join('\n')}\n`;

  function download() {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'codigos-de-recuperacion.txt';
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <section className="card stack" aria-labelledby="codigos">
      <h2 id="codigos">Guarda tus códigos de recuperación</h2>
      <Alert tone="warn">
        Si pierdes el teléfono, estos códigos son la única forma de entrar. Guárdalos fuera del celular (impresos o en tu
        gestor de contraseñas). No los volverás a ver.
      </Alert>
      <ul className="recovery-codes mono" aria-label="Códigos de recuperación">
        {codes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
      <div className="row wrap">
        <button type="button" className="btn btn-secondary" onClick={download}>
          Descargar .txt
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => void navigator.clipboard?.writeText(text).then(() => setCopied(true), () => setCopied(false))}
        >
          {copied ? 'Copiados' : 'Copiar'}
        </button>
        <button type="button" className="btn btn-primary right" onClick={onDone}>
          Ya los guardé
        </button>
      </div>
    </section>
  );
}

function EnabledCard({ status, onCodes, onDisabled }: { status: Status; onCodes: (codes: string[]) => void; onDisabled: () => void }) {
  const [mode, setMode] = useState<'idle' | 'regenerate' | 'disable'>('idle');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function open(next: typeof mode) {
    setMode(next);
    setCode('');
    setPassword('');
    setError(null);
  }

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      if (mode === 'regenerate') {
        const res = await http.post<{ recoveryCodes: string[] }>('/api/v1/account/two-factor/recovery-codes', { code: code.trim() });
        onCodes(res.recoveryCodes);
      } else {
        await http.post('/api/v1/account/two-factor/disable', { password, code: code.trim() });
        onDisabled();
      }
      open('idle');
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack" aria-labelledby="dos-pasos">
      <div className="row">
        <h2 id="dos-pasos">Verificación en dos pasos</h2>
        <span className="badge badge-ok right">Activa</span>
      </div>
      <p>Al entrar te pedimos el código de tu app de autenticación además de la contraseña.</p>
      <p className="small muted">Te quedan {status.recoveryCodesLeft} códigos de recuperación sin usar.</p>
      {status.recoveryCodesLeft <= 3 && (
        <Alert tone="warn">Te quedan pocos códigos de recuperación. Genera nuevos y guárdalos.</Alert>
      )}
      {mode === 'idle' ? (
        <div className="row wrap">
          <button type="button" className="btn btn-secondary" onClick={() => open('regenerate')}>
            Generar códigos de recuperación nuevos
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => open('disable')}>
            Desactivar
          </button>
        </div>
      ) : (
        <div className="stack" role="group" aria-label={mode === 'disable' ? 'Desactivar verificación en dos pasos' : 'Generar códigos nuevos'}>
          {error && <Alert tone="error">{error}</Alert>}
          {mode === 'disable' && (
            <Field label="Tu contraseña" type="password" autoComplete="current-password" value={password}
              onChange={(e) => setPassword(e.target.value)} />
          )}
          <Field label="Código de la app o de recuperación" autoComplete="one-time-code" value={code}
            onChange={(e) => setCode(e.target.value)}
            hint={mode === 'regenerate' ? 'Los códigos anteriores dejarán de servir.' : undefined} />
          <div className="row">
            <button type="button" className={mode === 'disable' ? 'btn btn-danger' : 'btn btn-primary'}
              disabled={busy || code.trim().length < 6 || (mode === 'disable' && !password)} onClick={() => void confirm()}>
              {busy ? 'Un momento…' : mode === 'disable' ? 'Desactivar' : 'Generar'}
            </button>
            <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => open('idle')}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
