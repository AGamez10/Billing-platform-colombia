import { NavLink, Outlet } from 'react-router';
import { useSession } from '../auth/session';
import { BRAND_NAME } from '../brand';
import { Icon } from './ui';

const NAV = [
  { to: '/', label: 'Inicio', icon: Icon.home, end: true },
  { to: '/vender', label: 'Vender', icon: Icon.cart, end: false },
  { to: '/facturas', label: 'Documentos', icon: Icon.invoice, end: false },
  { to: '/clientes', label: 'Clientes', icon: Icon.people, end: false },
  { to: '/productos', label: 'Productos', icon: Icon.box, end: false },
  { to: '/configuracion', label: 'Ajustes', icon: Icon.gear, end: false },
];

/** En pantallas anchas, debajo de lo diario: lo de la cuenta. En el celular se llega desde Ajustes. */
const ACCOUNT_NAV = [
  { to: '/equipo', label: 'Equipo', perm: 'manage' as const },
  { to: '/plan', label: 'Mi plan', perm: 'usage' as const },
];

export function Layout() {
  const { state, logout, can } = useSession();
  const orgName = state.status === 'signed-in' ? state.me.organizationName : null;

  return (
    <div className="app">
      <aside className="sidebar">
        <NavLink to="/" className="brand">
          {Icon.logo}
          <span>{BRAND_NAME}</span>
        </NavLink>
        <nav className="sidenav" aria-label="Principal">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end}>
              {item.icon}
              {item.label}
            </NavLink>
          ))}
        </nav>
        <nav className="sidenav sidenav-account" aria-label="Cuenta">
          {ACCOUNT_NAV.filter((i) => can(i.perm)).map((item) => (
            <NavLink key={item.to} to={item.to}>
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          {orgName && <span className="org-name">{orgName}</span>}
          <button type="button" className="btn btn-ghost" onClick={() => void logout()}>
            Cerrar sesión
          </button>
        </div>
      </aside>

      <div className="main-col">
        <header className="topbar">
          <NavLink to="/" className="brand">
            {Icon.logo}
            <span>{BRAND_NAME}</span>
          </NavLink>
          {orgName && <span className="org-name">{orgName}</span>}
        </header>
        <main className="main" id="contenido">
          <div className="main-inner">
            <Outlet />
          </div>
        </main>
      </div>

      <nav className="bottomnav" aria-label="Principal">
        {NAV.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end}>
            {item.icon}
            {item.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
