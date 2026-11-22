import { BRAND_NAME } from '../brand';

/**
 * Logotipo: «nítido» con la tilde en oro, el sello del documento aceptado. Con otro nombre
 * (marca blanca) se escribe el texto tal cual.
 */
export function Wordmark({ className = '' }: { className?: string }) {
  if (BRAND_NAME !== 'Nítido') return <span className={`wordmark ${className}`}>{BRAND_NAME}</span>;
  return (
    <span className={`wordmark ${className}`} aria-label={BRAND_NAME} role="img">
      <span aria-hidden="true">
        n<span className="wordmark-i">ı<span className="wordmark-accent" /></span>tido
      </span>
    </span>
  );
}
