import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import { useSession } from './auth/session';
import { Layout } from './components/Layout';
import { Loading } from './components/ui';
import { CustomerEditor, CustomerList } from './pages/Customers';
import { Home } from './pages/Home';
import { InvoiceDetail } from './pages/InvoiceDetail';
import { InvoiceEditor } from './pages/InvoiceEditor';
import { InvoiceList } from './pages/Invoices';
import { Legal } from './pages/Legal';
import { Login } from './pages/Login';
import { ProductEditor, ProductList } from './pages/Products';
import { Register } from './pages/Register';
import { Sell } from './pages/Sell';
import { Settings } from './pages/Settings';

function RequireSession({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const location = useLocation();
  if (state.status === 'loading') return <Loading />;
  if (state.status === 'signed-out') return <Navigate to="/entrar" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { state } = useSession();
  if (state.status === 'loading') return <Loading />;
  if (state.status === 'signed-in') return <Navigate to="/" replace />;
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/entrar" element={<PublicOnly><Login /></PublicOnly>} />
      <Route path="/registro" element={<PublicOnly><Register /></PublicOnly>} />
      <Route path="/terminos" element={<Legal title="Términos y condiciones" />} />
      <Route path="/privacidad" element={<Legal title="Política de tratamiento de datos personales" />} />
      <Route element={<RequireSession><Layout /></RequireSession>}>
        <Route index element={<Home />} />
        <Route path="vender" element={<Sell />} />
        <Route path="facturas" element={<InvoiceList />} />
        <Route path="facturas/nueva" element={<InvoiceEditor />} />
        <Route path="facturas/:id" element={<InvoiceDetail />} />
        <Route path="facturas/:id/editar" element={<InvoiceEditor />} />
        <Route path="clientes" element={<CustomerList />} />
        <Route path="clientes/nuevo" element={<CustomerEditor />} />
        <Route path="clientes/:id" element={<CustomerEditor />} />
        <Route path="productos" element={<ProductList />} />
        <Route path="productos/nuevo" element={<ProductEditor />} />
        <Route path="productos/:id" element={<ProductEditor />} />
        <Route path="configuracion" element={<Settings />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
