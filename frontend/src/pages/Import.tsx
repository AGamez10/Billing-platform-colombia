import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { http } from '../api/client';
import type { ImportResult } from '../api/types';
import { Alert, PageHead } from '../components/ui';
import { messageFor } from '../lib/errors';

const KINDS = {
  clientes: {
    title: 'Importar clientes',
    path: '/api/v1/customers/import',
    template: '/api/v1/public/import-templates/customers.csv',
    back: '/clientes',
    columns: 'tipo_documento (NIT, CC, CE, PA, TI, DE), numero, dv (opcional: se calcula), nombre, correo, telefono, direccion, municipio (código DANE), tipo_persona, regimen (48 o 49), responsabilidades (O-13|O-23…)',
  },
  productos: {
    title: 'Importar productos',
    path: '/api/v1/products/import',
    template: '/api/v1/public/import-templates/products.csv',
    back: '/productos',
    columns: 'nombre, sku, tipo (producto o servicio), unidad (94 = unidad), precio (sin impuestos), impuestos (IVA_19, IVA_5, IVA_EXCLUIDO… separados por |)',
  },
} as const;

/**
 * Traer clientes o productos desde Excel o el sistema anterior: primero una vista previa que no
 * guarda nada y muestra los errores por línea; luego se confirman solo las filas válidas.
 */
export function Import() {
  const [params] = useSearchParams();
  const kind = params.get('tipo') === 'productos' ? 'productos' : 'clientes';
  const cfg = KINDS[kind];
  const [csv, setCsv] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(text: string, commit: boolean) {
    setBusy(true);
    setError(null);
    try {
      setResult(await http.postCsv<ImportResult>(`${cfg.path}?commit=${commit}`, text));
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  async function pick(file: File | undefined) {
    setResult(null);
    if (!file) return;
    if (file.size > 1_000_000) return setError('El archivo pesa más de 1 MB. Divídelo en partes.');
    const text = await file.text();
    setCsv(text);
    setFileName(file.name);
    void run(text, false);
  }

  return (
    <>
      <PageHead title={cfg.title} subtitle="Desde Excel, Google Sheets o tu sistema anterior, guardado como CSV." />
      <section className="card stack" aria-labelledby="steps-h">
        <h2 id="steps-h">Cómo hacerlo</h2>
        <ol className="plain-steps">
          <li>
            Descarga la <a href={cfg.template}>plantilla</a> o usa tu propio archivo con estas columnas: <span className="small">{cfg.columns}</span>.
          </li>
          <li>Guárdalo como CSV (en Excel: Archivo → Guardar como → CSV UTF-8).</li>
          <li>Súbelo aquí: primero verás qué filas sirven y cuáles tienen errores; nada se guarda hasta que confirmes.</li>
        </ol>
        <label className="file-drop">
          <input type="file" accept=".csv,text/csv" onChange={(e) => void pick(e.target.files?.[0])} />
          <span>{fileName ? `Archivo: ${fileName}` : 'Elegir archivo CSV'}</span>
        </label>
      </section>

      {error && <Alert tone="error">{error}</Alert>}
      {busy && <Alert tone="info">Revisando el archivo…</Alert>}

      {result && (
        <section className="card stack" aria-labelledby="result-h" aria-live="polite">
          <h2 id="result-h">{result.committed ? 'Importación terminada' : 'Vista previa'}</h2>
          {result.committed ? (
            <Alert tone="ok">
              Se crearon {result.created} {kind}. <Link to={cfg.back}>Ver {kind}</Link>
            </Alert>
          ) : (
            <p>
              <strong>{result.valid}</strong> de {result.rows} filas se pueden importar.
              {result.errors.length > 0 && ` ${result.errors.length} tienen problemas y no se importarán.`}
            </p>
          )}
          {result.errors.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th className="num">Línea</th>
                    <th>Problema</th>
                  </tr>
                </thead>
                <tbody>
                  {result.errors.slice(0, 200).map((e) => (
                    <tr key={`${e.line}-${e.message}`}>
                      <td className="num">{e.line}</td>
                      <td>{e.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {!result.committed && result.valid > 0 && csv && (
            <div className="row">
              <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void run(csv, true)}>
                Importar {result.valid} {kind}
              </button>
              <span className="small muted">Las filas con problemas se omiten; puedes corregirlas y subirlas después.</span>
            </div>
          )}
        </section>
      )}
    </>
  );
}
