import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { BRAND_NAME } from '../brand';
import { Icon } from './ui';

/**
 * Marco de las pantallas sin sesión. En pantallas anchas, a la izquierda va lo que nos mueve:
 * es lo primero que una organización ve de nosotros, antes incluso de entrar.
 */
export function AuthShell({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  return (
    <div className="auth-shell">
      <aside className="auth-aside" aria-label={`Sobre ${BRAND_NAME}`}>
        <Link to="/" className="brand">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </Link>
        <div className="auth-aside-body">
          <p className="auth-aside-quote">Facturar bien no debería costarle el día a nadie.</p>
          <p>
            Emitimos tus facturas y tiquetes POS electrónicos a través de un proveedor tecnológico autorizado por la DIAN, y parte de cada
            suscripción paga la facturación de refugios y fundaciones que no pueden costearla.
          </p>
        </div>
        <p className="auth-aside-foot">Hecho en Colombia</p>
      </aside>
      <main className={`auth-main${wide ? ' wide' : ''}`}>
        <Link to="/" className="brand auth-brand-mobile">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </Link>
        <div className="auth-card">{children}</div>
        <p className="auth-legal small muted">
          <Link to="/terminos">Términos</Link> · <Link to="/privacidad">Tratamiento de datos</Link>
        </p>
      </main>
    </div>
  );
}
