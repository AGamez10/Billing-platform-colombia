import { useMemo, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import type { IdentificationType } from '../api/types';
import { useSession } from '../auth/session';
import { AuthShell } from '../components/AuthShell';
import { Alert, Field, SelectField } from '../components/ui';
import { fieldErrors, messageFor } from '../lib/errors';
import { ID_TYPE_LABEL } from '../lib/format';
import { cleanIdNumber, nitCheckDigit } from '../lib/nit';
import { useApi } from '../lib/useApi';

const ISSUER_TYPES: IdentificationType[] = ['NIT', 'CEDULA_CIUDADANIA', 'CEDULA_EXTRANJERIA'];

export function Register() {
  const { register } = useSession();
  const navigate = useNavigate();
  const [form, setForm] = useState({
    organizationName: '',
    identificationType: 'NIT' as IdentificationType,
    identificationNumber: '',
    checkDigit: '',
    fullName: '',
    email: '',
    password: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const legal = useApi<{ termsVersion: string; privacyVersion: string }>('/api/v1/public/legal');

  const set = (key: keyof typeof form) => (value: string) => setForm((f) => ({ ...f, [key]: value }));
  const expectedDv = useMemo(
    () => (form.identificationType === 'NIT' ? nitCheckDigit(cleanIdNumber(form.identificationNumber)) : null),
    [form.identificationType, form.identificationNumber],
  );
  const dvMismatch = form.identificationType === 'NIT' && form.checkDigit !== '' && expectedDv !== null && Number(form.checkDigit) !== expectedDv;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      await register({
        organizationName: form.organizationName.trim(),
        identificationType: form.identificationType,
        identificationNumber: cleanIdNumber(form.identificationNumber),
        checkDigit: form.identificationType === 'NIT' && form.checkDigit !== '' ? Number(form.checkDigit) : undefined,
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
        acceptedTermsVersion: legal.data?.termsVersion ?? '',
        acceptedPrivacyVersion: legal.data?.privacyVersion ?? '',
      });
      navigate('/', { replace: true });
    } catch (err) {
      setError(messageFor(err));
      setFields(fieldErrors(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell wide>
        <div className="stack">
          <h1>Crea tu cuenta</h1>
          <p className="muted">20 documentos gratis para probar. Sin tarjeta.</p>
        </div>
        {error && <Alert tone="error">{error}</Alert>}
        <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
          <Field
            label="Nombre o razón social del negocio"
            required
            autoComplete="organization"
            value={form.organizationName}
            onChange={(e) => set('organizationName')(e.target.value)}
            error={fields.organizationName}
          />
          <SelectField label="Tipo de identificación" value={form.identificationType} onChange={(e) => set('identificationType')(e.target.value)}>
            {ISSUER_TYPES.map((t) => (
              <option key={t} value={t}>
                {ID_TYPE_LABEL[t]}
              </option>
            ))}
          </SelectField>
          <div className="grid-2">
            <Field
              label={form.identificationType === 'NIT' ? 'NIT (sin dígito de verificación)' : 'Número'}
              inputMode="numeric"
              required
              value={form.identificationNumber}
              onChange={(e) => set('identificationNumber')(e.target.value)}
              error={fields.identificationNumber}
            />
            {form.identificationType === 'NIT' && (
              <Field
                label="Dígito de verificación"
                inputMode="numeric"
                maxLength={1}
                value={form.checkDigit}
                onChange={(e) => set('checkDigit')(e.target.value.replace(/\D/g, ''))}
                hint={expectedDv !== null && form.checkDigit === '' ? `Según el NIT debería ser ${expectedDv}` : undefined}
                error={dvMismatch ? `No coincide con el NIT (debería ser ${expectedDv}). Revisa tu RUT.` : undefined}
              />
            )}
          </div>
          <Field label="Tu nombre" required autoComplete="name" value={form.fullName} onChange={(e) => set('fullName')(e.target.value)} error={fields.fullName} />
          <Field label="Tu correo" type="email" required autoComplete="email" value={form.email} onChange={(e) => set('email')(e.target.value)} error={fields.email} />
          <Field
            label="Contraseña"
            type="password"
            required
            autoComplete="new-password"
            minLength={12}
            hint="Mínimo 12 caracteres. Una frase de varias palabras es fácil de recordar y segura."
            value={form.password}
            onChange={(e) => set('password')(e.target.value)}
            error={fields.password}
          />
          <label className="consent">
            <input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} required />
            <span>
              Acepto los <a href="/terminos" target="_blank" rel="noreferrer">términos y condiciones</a> y autorizo el tratamiento de mis datos según la{' '}
              <a href="/privacidad" target="_blank" rel="noreferrer">política de datos personales</a>.
            </span>
          </label>
          <button type="submit" className="btn btn-primary btn-block" disabled={busy || dvMismatch || !accepted || !legal.data}>
            {busy ? 'Creando tu cuenta…' : 'Crear cuenta gratis'}
          </button>
        </form>
        <p className="muted small">
          ¿Ya tienes cuenta? <Link to="/entrar">Inicia sesión</Link>
        </p>
    </AuthShell>
  );
}
