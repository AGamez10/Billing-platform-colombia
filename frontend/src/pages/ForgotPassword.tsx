import { useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { http } from '../api/client';
import { AuthShell } from '../components/AuthShell';
import { Alert, Field } from '../components/ui';
import { messageFor } from '../lib/errors';

export function ForgotPassword() {
  const location = useLocation();
  const [email, setEmail] = useState(() => {
    const from = (location.state as { email?: unknown } | null)?.email;
    return typeof from === 'string' ? from : '';
  });
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await http.post('/api/v1/auth/password-reset/request', { email: email.trim() });
      setSent(true);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell>
      {sent ? (
        <div className="stack">
          <h1>Revisa tu correo</h1>
          <p>
            Si <strong>{email.trim()}</strong> tiene una cuenta, te llegará un enlace para elegir una contraseña nueva. Vence en 30 minutos.
          </p>
          <p className="muted small">¿No llega? Revisa la carpeta de spam o espera un minuto antes de pedir otro.</p>
          <Link to="/entrar" className="btn btn-secondary btn-block">
            Volver a iniciar sesión
          </Link>
        </div>
      ) : (
        <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
          <div className="stack tight-stack">
            <h1>¿Olvidaste tu contraseña?</h1>
            <p className="muted">Escribe el correo con el que entras y te enviamos un enlace para cambiarla.</p>
          </div>
          {error && <Alert tone="error">{error}</Alert>}
          <Field label="Correo" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          <button type="submit" className="btn btn-primary btn-block" disabled={busy || !email.includes('@')}>
            {busy ? 'Enviando…' : 'Enviar enlace'}
          </button>
          <p className="small muted">
            <Link to="/entrar">Volver a iniciar sesión</Link>
          </p>
        </form>
      )}
    </AuthShell>
  );
}
