import type { DocumentSheet, SheetParty } from '../api/types';
import { formatCop, formatDate, formatDateTime, formatIdentification } from '../lib/format';
import { VAT_REGIME_LABEL } from '../lib/fiscal';
import { QrCode } from './QrCode';

/**
 * Representación gráfica imprimible (carta o A4; también se guarda como PDF desde el navegador).
 * Lo que no tiene validez tributaria lo dice en grande, arriba y en el pie: nunca se confunde.
 */
export function DocumentSheetView({ sheet }: { sheet: DocumentSheet }) {
  const pos = sheet.kind === 'POS_EQUIVALENT';
  const title = pos ? 'Documento equivalente electrónico — tiquete de máquina registradora con sistema POS' : 'Factura electrónica de venta';
  const keyLabel = pos ? 'CUDE' : 'CUFE';
  const warning = sheet.legallyValid
    ? null
    : sheet.simulated
      ? 'DOCUMENTO DE PRÁCTICA — no se envió a la DIAN y no tiene validez tributaria'
      : 'DOCUMENTO DE HABILITACIÓN (PRUEBAS) — no tiene validez tributaria';
  const a = sheet.authorization;

  return (
    <article className="doc-sheet" aria-label={`${title} ${sheet.number}`}>
      {warning && <p className="doc-warning">{warning}</p>}
      <header className="doc-head">
        <div>
          <h1 className="doc-issuer">{sheet.issuer.legalName}</h1>
          <PartyLines party={sheet.issuer} />
        </div>
        <div className="doc-number">
          <span>{title}</span>
          <strong>{sheet.number}</strong>
          <span>Fecha de expedición: {formatDate(sheet.issueDate)}</span>
          <span>Validación: {formatDateTime(sheet.validatedAt)}</span>
        </div>
      </header>

      <section className="doc-block">
        <h2>Adquiriente</h2>
        {sheet.finalConsumer ? (
          <p>Consumidor final</p>
        ) : (
          <>
            <p className="doc-strong">{sheet.customer.legalName}</p>
            <PartyLines party={sheet.customer} />
          </>
        )}
      </section>

      <table className="doc-lines">
        <thead>
          <tr>
            <th>#</th>
            <th>Descripción</th>
            <th className="num">Cant.</th>
            <th className="num">Valor unitario</th>
            <th className="num">Descuento</th>
            <th className="num">Impuestos</th>
            <th className="num">Total</th>
          </tr>
        </thead>
        <tbody>
          {sheet.lines.map((l) => (
            <tr key={l.lineNumber}>
              <td>{l.lineNumber}</td>
              <td>{l.description}</td>
              <td className="num">{l.quantity}</td>
              <td className="num">{formatCop(l.unitPrice)}</td>
              <td className="num">{l.discountAmount ? `−${formatCop(l.discountAmount)}` : '—'}</td>
              <td className="num">{formatCop(l.taxTotal)}</td>
              <td className="num">{formatCop(l.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="doc-foot">
        <div className="doc-legal">
          <p>
            <strong>Autorización de numeración DIAN</strong> No. {a.resolutionNumber} del {formatDate(a.validFrom)}, vigente hasta{' '}
            {formatDate(a.validTo)}. Prefijo {a.prefix || '—'}, rango {a.rangeFrom} a {a.rangeTo}.
          </p>
          <p className="doc-key">
            <strong>{keyLabel}:</strong> <span className="mono">{sheet.documentKey}</span>
          </p>
          {sheet.qrPayload ? (
            <div className="doc-qr">
              <QrCode value={sheet.qrPayload} label={`Código QR del ${keyLabel}`} size={132} />
            </div>
          ) : (
            <p className="small muted">El proveedor no informó código QR para este documento.</p>
          )}
        </div>
        <dl className="doc-totals">
          <dt>Subtotal</dt>
          <dd>{formatCop(sheet.totals.lineExtensionAmount)}</dd>
          {sheet.taxSubtotals.map((t) => (
            <Row key={t.code} label={`${t.type} ${t.percentage} %`} value={formatCop(t.taxAmount)} />
          ))}
          <dt className="grand">Total a pagar</dt>
          <dd className="grand">{formatCop(sheet.totals.payableAmount)}</dd>
        </dl>
      </div>
      {warning && <p className="doc-warning doc-warning-foot">{warning}</p>}
    </article>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

function PartyLines({ party }: { party: SheetParty }) {
  const place = [party.address, [party.cityName, party.departmentName].filter(Boolean).join(', ')].filter(Boolean).join(' · ');
  const fiscal = [
    party.vatRegime ? VAT_REGIME_LABEL[party.vatRegime] : null,
    party.responsibilities.length ? `Responsabilidades: ${party.responsibilities.join(', ')}` : null,
  ].filter(Boolean);
  return (
    <p className="doc-party">
      {formatIdentification(party.identificationType, party.identificationNumber, party.checkDigit ?? undefined)}
      {place && (
        <>
          <br />
          {place}
        </>
      )}
      {party.email && (
        <>
          <br />
          {party.email}
        </>
      )}
      {fiscal.length > 0 && (
        <>
          <br />
          {fiscal.join(' · ')}
        </>
      )}
    </p>
  );
}
