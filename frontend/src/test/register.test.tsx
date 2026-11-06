import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SessionProvider } from '../auth/session';
import { Register } from '../pages/Register';

describe('registro', () => {
  beforeEach(() => {
    // Sin sesión previa: la renovación inicial responde 401.
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"code":"INVALID_REFRESH_TOKEN"}', { status: 401 })));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('avisa cuando el dígito de verificación no corresponde al NIT', async () => {
    render(
      <MemoryRouter>
        <SessionProvider>
          <Register />
        </SessionProvider>
      </MemoryRouter>,
    );
    await userEvent.type(screen.getByLabelText(/NIT \(sin dígito/), '800197268');
    expect(screen.getByText(/debería ser 4/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Dígito de verificación'), '9');
    expect(screen.getByText(/No coincide con el NIT/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Crear cuenta gratis/ })).toBeDisabled();
  });
});
