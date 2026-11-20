import { useState, type FormEvent } from 'react';
import { useSession } from '../auth/session';
import { messageFor } from '../lib/errors';
import { Alert, Field } from './ui';

/**
 * Segundo paso del inicio de sesión. El reto vive solo en memoria de esta pantalla: si se
 * recarga, se vuelve a pedir la contraseña.
 */
export function TwoFactorStep({ challenge, onDone, onRestart }: { challenge: string; onDone: () => void; onRestart: () => void }) {
  const { completeTwoFactor } = useSession();
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await completeTwoFactor(challenge, code.trim());
      onDone();
    } catch (err) {
      setError(messageFor(err));
      setCode('');
    } finally {
      setBusy(false);
    }
  }

  const ready = recovery ? code.replace(/[\s-]/g, '').length === 10 : /^\d{6}$/.test(code.replace(/\s/g, ''));

  return (
    <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
      <div className="stack">
        <h1>Verificación en dos pasos</h1>
        <p className="muted">
          {recovery
            ? 'Escribe uno de los códigos de recuperación que guardaste. Cada uno sirve una sola vez.'
            : 'Abre tu app de autenticación y escribe el código de 6 dígitos de esta cuenta.'}
        </p>
      </div>
      {error && <Alert tone="error">{error}</Alert>}
      {recovery ? (
        <Field
          key="recovery"
          label="Código de recuperación"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          placeholder="XXXXX-XXXXX"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          autoFocus
        />
      ) : (
        <Field
          key="totp"
          label="Código de 6 dígitos"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={7}
          value={code}
          onChange={(e) => setCode(e.target.value)}
          autoFocus
        />
      )}
      <button type="submit" className="btn btn-primary btn-block" disabled={busy || !ready}>
        {busy ? 'Verificando…' : 'Entrar'}
      </button>
      <div className="row">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setRecovery(!recovery); setCode(''); setError(null); }}>
          {recovery ? 'Usar la app de autenticación' : 'No tengo el teléfono'}
        </button>
        <button type="button" className="btn btn-ghost btn-sm right" onClick={onRestart}>
          Volver
        </button>
      </div>
    </form>
  );
}
