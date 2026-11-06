import { useState } from 'react';
import { Link } from 'react-router';
import type { InvoiceStatus, InvoiceSummary, Page } from '../api/types';
import { useSession } from '../auth/session';
import { Alert, Empty, EnvironmentBadge, Icon, PageHead, Spinner, StatusBadge } from '../components/ui';
import { messageFor } from '../lib/errors';
import { formatCop, formatDate, STATUS_LABEL } from '../lib/format';
import { useApi } from '../lib/useApi';

const FILTERS: (InvoiceStatus | '')[] = ['', 'DRAFT', 'SUBMITTED', 'ACCEPTED', 'REJECTED'];
const PAGE_SIZE = 25;

export function InvoiceList() {
  const { can } = useSession();
  const [status, setStatus] = useState<InvoiceStatus | ''>('');
  const [offset, setOffset] = useState(0);
  const list = useApi<Page<InvoiceSummary>>(`/api/v1/invoices?limit=${PAGE_SIZE}&offset=${offset}${status ? `&status=${status}` : ''}`);

  return (
    <>
      <PageHead
        title="Facturas"
        action={
          can('write') && (
            <Link to="/facturas/nueva" className="btn btn-primary">
              {Icon.plus} Nueva factura
            </Link>
          )
        }
      />
      <div className="checks" role="group" aria-label="Filtrar por estado">
        {FILTERS.map((f) => (
          <label key={f || 'all'} className="check">
            <input
              type="radio"
              name="status"
              checked={status === f}
              onChange={() => {
                setStatus(f);
                setOffset(0);
              }}
            />
            {f ? STATUS_LABEL[f] : 'Todas'}
          </label>
        ))}
      </div>
      <section className="card tight">
        {list.loading && !list.data ? (
          <div className="empty">
            <Spinner />
          </div>
        ) : list.error ? (
          <div className="card-head">
            <Alert tone="error">{messageFor(list.error)}</Alert>
          </div>
        ) : list.data && list.data.items.length > 0 ? (
          <ul className="list">
            {list.data.items.map((inv) => (
              <li key={inv.id}>
                <Link to={`/facturas/${inv.id}`} className="list-item">
                  <div className="grow">
                    <div className="title">{inv.customerName}</div>
                    <div className="sub">
                      {inv.number ?? 'Borrador'} · {formatDate(inv.issueDate)}
                    </div>
                  </div>
                  <div className="amount-col">
                    <span className="num">{formatCop(inv.payableAmount)}</span>
                    <StatusBadge status={inv.status} />
                    <EnvironmentBadge environment={inv.environment} />
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <Empty
            title={status ? 'No hay facturas con ese estado' : 'Aún no tienes facturas'}
            action={
              !status &&
              can('write') && (
                <Link to="/facturas/nueva" className="btn btn-primary">
                  Crear la primera
                </Link>
              )
            }
          />
        )}
      </section>
      {list.data && (offset > 0 || list.data.hasMore) && (
        <div className="row">
          <button type="button" className="btn btn-secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
            Anteriores
          </button>
          <button type="button" className="btn btn-secondary right" disabled={!list.data.hasMore} onClick={() => setOffset(offset + PAGE_SIZE)}>
            Siguientes
          </button>
        </div>
      )}
    </>
  );
}
