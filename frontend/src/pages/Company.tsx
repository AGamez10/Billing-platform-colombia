import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { http } from '../api/client';
import type { FiscalProfile, OrganizationProfile } from '../api/types';
import { useSession } from '../auth/session';
import { FiscalFields } from '../components/FiscalFields';
import { MunicipalityPicker } from '../components/MunicipalityPicker';
import { Alert, Field, Loading, PageHead } from '../components/ui';
import { fieldErrors, messageFor } from '../lib/errors';
import { ID_TYPE_LABEL } from '../lib/format';
import { defaultFiscal } from '../lib/fiscal';
import { useApi } from '../lib/useApi';

interface Form {
  legalName: string;
  tradeName: string;
  email: string;
  phone: string;
  addressLine: string;
  cityCode: string;
  fiscal: FiscalProfile;
}

/** Datos del emisor: lo que aparece en cada documento y lo que el proveedor envía a la DIAN. */
export function Company() {
  const { can } = useSession();
  const profile = useApi<OrganizationProfile>('/api/v1/organization');
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const readOnly = !can('manage');

  useEffect(() => {
    const p = profile.data;
    if (!p) return;
    setForm({
      legalName: p.legalName,
      tradeName: p.tradeName ?? '',
      email: p.email ?? '',
      phone: p.phone ?? '',
      addressLine: p.address?.line ?? '',
      cityCode: p.address?.cityCode ?? '',
      fiscal: p.fiscal ?? defaultFiscal(p.identificationType),
    });
  }, [profile.data]);

  if (profile.error) return <Alert tone="error">{messageFor(profile.error)}</Alert>;
  if (!profile.data || !form) return <Loading />;
  const p = profile.data;
  const set = <K extends keyof Form>(key: K) => (value: Form[K]) => {
    setSaved(false);
    setForm((f) => (f ? { ...f, [key]: value } : f));
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    setBusy(true);
    setError(null);
    setFields({});
    try {
      await http.put<OrganizationProfile>('/api/v1/organization', {
        legalName: form.legalName.trim(),
        tradeName: form.tradeName.trim() || undefined,
        email: form.email.trim(),
        phone: form.phone.trim() || undefined,
        addressLine: form.addressLine.trim(),
        cityCode: form.cityCode,
        fiscal: { personType: form.fiscal.personType, vatRegime: form.fiscal.vatRegime, responsibilities: form.fiscal.responsibilities },
      });
      await profile.reload();
      setSaved(true);
    } catch (err) {
      setError(messageFor(err));
      setFields(fieldErrors(err));
    } finally {
      setBusy(false);
    }
  }

  const idText = `${ID_TYPE_LABEL[p.identificationType]} ${p.identificationNumber}${p.checkDigit != null ? `-${p.checkDigit}` : ''}`;

  return (
    <>
      <PageHead
        title="Datos de tu empresa"
        subtitle="Así apareces en cada factura y así te identifica la DIAN. Cópialos de tu RUT actualizado."
      />
      {p.readyToIssue ? (
        <Alert tone="ok">Tus datos tributarios están completos: puedes emitir en habilitación y producción.</Alert>
      ) : (
        <Alert tone="warn">
          Puedes practicar sin estos datos, pero son obligatorios para facturar en real. Ten a mano tu RUT.
        </Alert>
      )}
      <form className="stack" onSubmit={(e) => void submit(e)} noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        {saved && <Alert tone="ok">Guardado.</Alert>}

        <section className="card stack" aria-labelledby="id-h">
          <div className="section-title">
            <h2 id="id-h">Identificación</h2>
            <span className="small muted">{idText}</span>
          </div>
          <p className="small muted">
            La identificación no se puede cambiar: es la identidad tributaria de esta cuenta. Si cambió tu NIT, escríbenos.
          </p>
          <div className="grid-2">
            <Field label="Razón social o nombre completo" required disabled={readOnly} value={form.legalName}
              onChange={(e) => set('legalName')(e.target.value)} error={fields.legalName} hint="Tal como figura en el RUT." />
            <Field label="Nombre comercial" disabled={readOnly} value={form.tradeName}
              onChange={(e) => set('tradeName')(e.target.value)} hint="Opcional. El nombre con el que te conocen." />
          </div>
          <div className="grid-2">
            <Field label="Correo de facturación" type="email" required disabled={readOnly} value={form.email}
              onChange={(e) => set('email')(e.target.value)} error={fields.email} hint="Donde recibes documentos de tus proveedores." />
            <Field label="Teléfono" type="tel" disabled={readOnly} value={form.phone} onChange={(e) => set('phone')(e.target.value)} />
          </div>
        </section>

        <section className="card stack" aria-labelledby="addr-h">
          <h2 id="addr-h">Dirección fiscal</h2>
          <div className="grid-2">
            <Field label="Dirección" required disabled={readOnly} value={form.addressLine}
              onChange={(e) => set('addressLine')(e.target.value)} error={fields.addressLine} placeholder="Calle 10 # 5-20, local 3" />
            <MunicipalityPicker required disabled={readOnly} value={form.cityCode} onChange={set('cityCode')} error={fields.cityCode} />
          </div>
        </section>

        <section className="card stack" aria-labelledby="fiscal-h">
          <h2 id="fiscal-h">Datos tributarios</h2>
          <FiscalFields whose="own" disabled={readOnly} value={form.fiscal} onChange={set('fiscal')} />
        </section>

        {readOnly ? (
          <p className="small muted">Solo el dueño o un administrador puede cambiar estos datos.</p>
        ) : (
          <div className="row">
            <button type="submit" className="btn btn-primary" disabled={busy || !form.cityCode}>
              {busy ? 'Guardando…' : 'Guardar datos de la empresa'}
            </button>
            <Link to="/configuracion" className="btn btn-secondary">
              Volver a Ajustes
            </Link>
          </div>
        )}
      </form>
    </>
  );
}
