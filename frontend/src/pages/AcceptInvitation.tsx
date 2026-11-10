import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { http } from '../api/client';
import type { InvitationPreview } from '../api/types';
import { useSession } from '../auth/session';
import { AuthShell } from '../components/AuthShell';
import { Alert, Field, Spinner } from '../components/ui';
import { messageFor } from '../lib/errors';
import { ROLE_DESCRIPTION, ROLE_LABEL } from '../lib/format';
import { useLinkToken } from '../lib/linkToken';
import { useApi } from '../lib/useApi';

export function AcceptInvitation() {
  const token = useLinkToken();
  const { acceptInvitation, state, logout } = useSession();
  const navigate = useNavigate();
  const legal = useApi<{ termsVersion: string; privacyVersion: string }>('/api/v1/public/legal');
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [accepted, setAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    http.post<InvitationPreview>('/api/v1/auth/invitations/preview', { token })
      .then(setPreview)
      .catch((err: unknown) => setLoadError(messageFor(err)));
  }, [token]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!token || !preview) return;
    setBusy(true);
    setError(null);
    try {
      if (state.status === 'signed-in') await logout();
      await acceptInvitation(
        preview.existingAccount
          ? { token, password }
          : {
              token,
              password,
              fullName: fullName.trim(),
              acceptedTermsVersion: legal.data?.termsVersion,
              acceptedPrivacyVersion: legal.data?.privacyVersion,
            },
      );
      navigate('/', { replace: true });
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  if (!token || loadError) {
    return (
      <AuthShell>
        <div className="stack">
          <h1>Esta invitación no se puede usar</h1>
          <p>{loadError ?? 'Abre el enlace directamente desde el correo que recibiste.'}</p>
          <Link to="/entrar" className="btn btn-secondary btn-block">
            Ir a iniciar sesión
          </Link>
        </div>
      </AuthShell>
    );
  }

  if (!preview) {
    return (
      <AuthShell>
        <Spinner label="Revisando la invitación" />
      </AuthShell>
    );
  }

  const canSubmit = preview.existingAccount
    ? password.length > 0
    : fullName.trim().length > 1 && password.length >= 12 && accepted && !!legal.data;

  return (
    <AuthShell>
      <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
        <div className="stack tight-stack">
          <h1>Únete a {preview.organizationName}</h1>
          <p className="muted">
            Te invitaron como <strong>{ROLE_LABEL[preview.role].toLowerCase()}</strong>: {ROLE_DESCRIPTION[preview.role].toLowerCase()}
          </p>
        </div>
        {error && <Alert tone="error">{error}</Alert>}
        <Field label="Correo" type="email" value={preview.email} readOnly hint="La invitación es para este correo." />
        {preview.existingAccount ? (
          <>
            <Field label="Tu contraseña" type="password" autoComplete="current-password" required value={password}
              hint="Ya tienes cuenta: usa la misma contraseña con la que entras." onChange={(e) => setPassword(e.target.value)} />
            <p className="small">
              <Link to="/olvide">Olvidé mi contraseña</Link>
            </p>
          </>
        ) : (
          <>
            <Field label="Tu nombre completo" autoComplete="name" required value={fullName} onChange={(e) => setFullName(e.target.value)} />
            <Field label="Crea una contraseña" type="password" autoComplete="new-password" required minLength={12} value={password}
              hint="Al menos 12 caracteres. Una frase de varias palabras funciona bien." onChange={(e) => setPassword(e.target.value)} />
            <label className="consent">
              <input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} />
              <span>
                Acepto los <Link to="/terminos" target="_blank">términos y condiciones</Link> y la{' '}
                <Link to="/privacidad" target="_blank">política de tratamiento de datos</Link>.
              </span>
            </label>
          </>
        )}
        <button type="submit" className="btn btn-primary btn-block" disabled={busy || !canSubmit}>
          {busy ? 'Entrando…' : `Unirme a ${preview.organizationName}`}
        </button>
      </form>
    </AuthShell>
  );
}
