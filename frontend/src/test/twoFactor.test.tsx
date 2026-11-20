import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SessionProvider } from '../auth/session';
import { TwoFactorStep } from '../components/TwoFactorStep';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('segundo paso del inicio de sesión', () => {
  const calls: { url: string; body: unknown }[] = [];

  beforeEach(() => {
    calls.length = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith('/api/v1/auth/login/two-factor')) {
          const body = JSON.parse(String(init?.body)) as { code: string };
          calls.push({ url, body });
          return body.code === '123456'
            ? json({ status: 'SIGNED_IN', accessToken: 'at', organization: { id: 'o', name: 'Org', role: 'OWNER' } })
            : json({ code: 'INVALID_TWO_FACTOR_CODE' }, 422);
        }
        if (url.endsWith('/api/v1/auth/me')) {
          return json({ userId: 'u', organizationId: 'o', organizationName: 'Org', role: 'OWNER', organizations: [] });
        }
        return json({ code: 'INVALID_REFRESH_TOKEN' }, 401);
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  function renderStep(onDone = vi.fn()) {
    render(
      <MemoryRouter>
        <SessionProvider>
          <TwoFactorStep challenge="reto" onDone={onDone} onRestart={vi.fn()} />
        </SessionProvider>
      </MemoryRouter>,
    );
    return onDone;
  }

  it('solo envía 6 dígitos; un código errado muestra el error y limpia el campo', async () => {
    const onDone = renderStep();
    const input = screen.getByLabelText('Código de 6 dígitos');
    const enter = screen.getByRole('button', { name: 'Entrar' });
    await userEvent.type(input, '12345');
    expect(enter).toBeDisabled();
    await userEvent.type(input, '9');
    await userEvent.click(enter);
    expect(await screen.findByText(/El código no es válido/)).toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(onDone).not.toHaveBeenCalled();
    expect(calls[0]?.body).toEqual({ challenge: 'reto', code: '123459' });
  });

  it('con el código correcto abre la sesión', async () => {
    const onDone = renderStep();
    await userEvent.type(screen.getByLabelText('Código de 6 dígitos'), '123 456');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
    await vi.waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('permite usar un código de recuperación', async () => {
    renderStep();
    await userEvent.click(screen.getByRole('button', { name: 'No tengo el teléfono' }));
    const input = screen.getByLabelText('Código de recuperación');
    await userEvent.type(input, 'ABCDE-FGHJK');
    await userEvent.click(screen.getByRole('button', { name: 'Entrar' }));
    await vi.waitFor(() => expect(calls[0]?.body).toEqual({ challenge: 'reto', code: 'ABCDE-FGHJK' }));
  });
});
