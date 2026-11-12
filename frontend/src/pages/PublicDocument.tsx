import { useEffect, useState } from 'react';
import { http } from '../api/client';
import type { DocumentSheet } from '../api/types';
import { BRAND_NAME } from '../brand';
import { DocumentSheetView } from '../components/DocumentSheetView';
import { Alert, Loading } from '../components/ui';
import { messageFor } from '../lib/errors';
import { useLinkToken } from '../lib/linkToken';

/** Lo que ve el comprador al abrir el enlace: su documento, sin cuenta ni datos internos. */
export function PublicDocument() {
  const token = useLinkToken();
  const [sheet, setSheet] = useState<DocumentSheet | null>(null);
  const [error, setError] = useState<string | null>(token ? null : 'El enlace está incompleto. Ábrelo de nuevo desde el correo o el mensaje.');

  useEffect(() => {
    if (!token) return;
    http
      .post<DocumentSheet>('/api/v1/public/documents/view', { token })
      .then((s) => {
        setSheet(s);
        document.title = `${s.number} · ${s.issuer.legalName}`;
      })
      .catch((err) => setError(messageFor(err)));
  }, [token]);

  if (error) {
    return (
      <main className="doc-public">
        <Alert tone="error">{error}</Alert>
      </main>
    );
  }
  if (!sheet) return <Loading />;
  return (
    <main className="doc-public doc-page">
      <div className="row no-print">
        <button type="button" className="btn btn-primary" onClick={() => window.print()}>
          Imprimir o guardar PDF
        </button>
      </div>
      <DocumentSheetView sheet={sheet} />
      <p className="small muted no-print">Enviado con {BRAND_NAME}.</p>
    </main>
  );
}
