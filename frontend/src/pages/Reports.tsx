import { useState } from 'react';
import { http } from '../api/client';
import type { SalesReport } from '../api/types';
import { Alert, Field, PageHead, Spinner } from '../components/ui';
import { messageFor } from '../lib/errors';
import { formatCop } from '../lib/format';
import { useApi } from '../lib/useApi';

const ENV_LABEL = { SIMULATION: 'práctica (sin validez tributaria)', HABILITATION: 'habilitación (sin validez tributaria)', PRODUCTION: 'producción' } as const;

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Ventas e impuestos del periodo, y el archivo para el contador. */
export function Reports() {
  const now = new Date();
  const [from, setFrom] = useState(iso(new Date(now.getFullYear(), now.getMonth(), 1)));
  const [to, setTo] = useState(iso(now));
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const report = useApi<SalesReport>(from && to ? `/api/v1/reports/sales?from=${from}&to=${to}` : null);
  const r = report.data;

  function preset(kind: 'month' | 'previous' | 'bimester' | 'year') {
    const y = now.getFullYear();
    const m = now.getMonth();
    if (kind === 'month') {
      setFrom(iso(new Date(y, m, 1)));
      setTo(iso(now));
    } else if (kind === 'previous') {
      setFrom(iso(new Date(y, m - 1, 1)));
      setTo(iso(new Date(y, m, 0)));
    } else if (kind === 'bimester') {
      // Bimestres calendario (ene-feb, mar-abr, …): el último completo.
      const start = m - (m % 2) - 2;
      setFrom(iso(new Date(y, start, 1)));
      setTo(iso(new Date(y, start + 2, 0)));
    } else {
      setFrom(iso(new Date(y, 0, 1)));
      setTo(iso(now));
    }
  }

  async function download() {
    setDownloading(true);
    setError(null);
    try {
      await http.download(`/api/v1/reports/sales.csv?from=${from}&to=${to}`, `ventas-${from}-a-${to}.csv`);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setDownloading(false);
    }
  }

  return (
    <>
      <PageHead title="Reportes" subtitle="Tus ventas validadas y sus impuestos en el periodo que elijas. Descárgalo para tu contador." />
      <section className="card stack" aria-labelledby="period-h">
        <h2 id="period-h">Periodo</h2>
        <div className="row wrap">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => preset('month')}>Este mes</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => preset('previous')}>Mes anterior</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => preset('bimester')}>Último bimestre</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => preset('year')}>Este año</button>
        </div>
        <div className="grid-2">
          <Field label="Desde" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <Field label="Hasta" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </section>

      {(report.error || error) && <Alert tone="error">{error ?? messageFor(report.error)}</Alert>}
      {report.loading && !r ? (
        <div className="empty">
          <Spinner />
        </div>
      ) : (
        r && (
          <section className="card stack" aria-labelledby="result-h">
            <div className="row card-head">
              <h2 id="result-h">Resultado</h2>
              <span className="right muted small">Ambiente: {ENV_LABEL[r.environment]}</span>
            </div>
            <div className="kpis">
              <div className="kpi">
                <span className="kpi-label">Documentos validados</span>
                <strong className="kpi-value num">{r.documents}</strong>
                {r.creditNotes > 0 && <span className="kpi-hint">y {r.creditNotes} notas crédito</span>}
              </div>
              <div className="kpi">
                <span className="kpi-label">Subtotal</span>
                <strong className="kpi-value num">{formatCop(r.subtotal)}</strong>
              </div>
              <div className="kpi">
                <span className="kpi-label">Impuestos</span>
                <strong className="kpi-value num">{formatCop(r.taxTotal)}</strong>
              </div>
              <div className="kpi">
                <span className="kpi-label">Total</span>
                <strong className="kpi-value num">{formatCop(r.total)}</strong>
              </div>
            </div>
            {r.taxes.length > 0 && (
              <div className="table-wrap">
                <table>
                  <caption className="visually-hidden">Impuestos por tarifa</caption>
                  <thead>
                    <tr>
                      <th>Impuesto</th>
                      <th className="num">Tarifa</th>
                      <th className="num">Base</th>
                      <th className="num">Valor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.taxes.map((t) => (
                      <tr key={`${t.type}-${t.percentage}`}>
                        <td>{t.type}</td>
                        <td className="num">{t.percentage} %</td>
                        <td className="num">{formatCop(t.taxable)}</td>
                        <td className="num">{formatCop(t.amount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="small muted">Las notas crédito ya están restadas. Este reporte ayuda a tu contador; no reemplaza tu declaración.</p>
            <div>
              <button type="button" className="btn btn-primary" disabled={downloading} onClick={() => void download()}>
                {downloading ? 'Preparando…' : 'Descargar para Excel (CSV)'}
              </button>
            </div>
          </section>
        )
      )}
    </>
  );
}
