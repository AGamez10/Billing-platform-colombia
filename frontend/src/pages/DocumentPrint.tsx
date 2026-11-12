import { Link, useParams } from 'react-router';
import type { DocumentSheet } from '../api/types';
import { DocumentSheetView } from '../components/DocumentSheetView';
import { Alert, Loading } from '../components/ui';
import { messageFor } from '../lib/errors';
import { useApi } from '../lib/useApi';

/** Representación gráfica dentro de la app: ver, imprimir o guardar como PDF. */
export function DocumentPrint() {
  const { id } = useParams();
  const sheet = useApi<DocumentSheet>(`/api/v1/invoices/${id}/sheet`);
  if (sheet.loading && !sheet.data) return <Loading />;
  if (sheet.error || !sheet.data) return <Alert tone="error">{messageFor(sheet.error)}</Alert>;
  return (
    <div className="doc-page">
      <div className="row no-print">
        <Link to={`/facturas/${id}`} className="btn btn-secondary">
          Volver
        </Link>
        <button type="button" className="btn btn-primary" onClick={() => window.print()}>
          Imprimir o guardar PDF
        </button>
      </div>
      <DocumentSheetView sheet={sheet.data} />
    </div>
  );
}
