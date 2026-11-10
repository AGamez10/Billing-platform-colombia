import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { http } from '../api/client';
import { AuthShell } from '../components/AuthShell';
import { Alert, Field } from '../components/ui';
import { messageFor } from '../lib/errors';
import { useLinkToken } from '../lib/linkToken';

export function ResetPassword() {
  const token = useLinkToken();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mismatch = confirm !== '' && confirm !== password;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await http.post('/api/v1/auth/password-reset/confirm', { token, password });
      setDone(true);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthShell>
        <div className="stack">
          <h1>Este enlace no está completo</h1>
          <p>Abre el enlace directamente desde el correo, o pide uno nuevo.</p>
          <Link to="/olvide" className="btn btn-primary btn-block">
            Pedir un enlace nuevo
          </Link>
        </div>
      </AuthShell>
    );
  }

  return (
    <AuthShell>
      {done ? (
        <div className="stack">
          <h1>Listo, tu contraseña cambió</h1>
          <p>Cerramos las sesiones que tenías abiertas en otros equipos. Entra con tu contraseña nueva.</p>
          <Link to="/entrar" className="btn btn-primary btn-block">
            Iniciar sesión
          </Link>
        </div>
      ) : (
        <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
          <div className="stack tight-stack">
            <h1>Elige una contraseña nueva</h1>
            <p className="muted">Usa al menos 12 caracteres. Una frase de varias palabras es fácil de recordar y difícil de adivinar.</p>
          </div>
          {error && <Alert tone="error">{error}</Alert>}
          <Field label="Contraseña nueva" type="password" autoComplete="new-password" required minLength={12} value={password}
            onChange={(e) => setPassword(e.target.value)} />
          <Field label="Repítela" type="password" autoComplete="new-password" required value={confirm}
            error={mismatch ? 'No coincide con la anterior.' : undefined} onChange={(e) => setConfirm(e.target.value)} />
          <button type="submit" className="btn btn-primary btn-block" disabled={busy || password.length < 12 || password !== confirm}>
            {busy ? 'Guardando…' : 'Guardar contraseña'}
          </button>
          {error && (
            <p className="small">
              <Link to="/olvide">Pedir un enlace nuevo</Link>
            </p>
          )}
        </form>
      )}
    </AuthShell>
  );
}
