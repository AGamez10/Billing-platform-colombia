import { Link } from 'react-router';

/** Los textos definitivos se publican cuando termine la revisión legal (ver docs/ROADMAP.md). */
export function Legal({ title }: { title: string }) {
  return (
    <div className="auth-shell">
      <main className="auth-card">
        <h1>{title}</h1>
        <p>Este documento está en revisión legal y se publicará aquí antes del lanzamiento.</p>
        <p className="muted small">Mientras tanto, puedes escribirnos para pedir una copia del borrador.</p>
        <Link to="/" className="btn btn-secondary">
          Volver
        </Link>
      </main>
    </div>
  );
}
