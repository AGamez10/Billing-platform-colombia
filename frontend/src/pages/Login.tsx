import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { useSession } from '../auth/session';
import { BRAND_NAME } from '../brand';
import { Alert, Field, Icon } from '../components/ui';
import { messageFor } from '../lib/errors';

export function Login() {
  const { login } = useSession();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [organizations, setOrganizations] = useState<{ id: string; name: string }[] | null>(null);

  async function submit(e: FormEvent, organizationId?: string) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await login(email.trim(), password, organizationId);
      if (session.status === 'ORGANIZATION_REQUIRED') {
        setOrganizations(session.organizations ?? []);
      } else {
        navigate('/', { replace: true });
      }
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-shell">
      <main className="auth-card">
        <span className="brand">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </span>
        <div className="stack">
          <h1>Inicia sesión</h1>
          <p className="muted">Factura electrónica para tu negocio.</p>
        </div>
        {error && <Alert tone="error">{error}</Alert>}

        {organizations ? (
          <div className="stack">
            <p>Perteneces a varias organizaciones. ¿Con cuál vas a trabajar?</p>
            {organizations.map((o) => (
              <button key={o.id} type="button" className="btn btn-secondary btn-block" disabled={busy} onClick={(e) => void submit(e, o.id)}>
                {o.name}
              </button>
            ))}
          </div>
        ) : (
          <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
            <Field label="Correo" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
            <Field
              label="Contraseña"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button type="submit" className="btn btn-primary btn-block" disabled={busy || !email || !password}>
              {busy ? 'Entrando…' : 'Entrar'}
            </button>
          </form>
        )}
        <p className="muted small">
          ¿No tienes cuenta? <Link to="/registro">Empieza gratis con 20 documentos</Link>
        </p>
      </main>
    </div>
  );
}
