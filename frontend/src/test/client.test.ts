import { ApiError, http, onSessionExpired, refreshSession, setAccessToken } from '../api/client';

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('cliente de la API', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    setAccessToken(null);
  });

  afterEach(() => vi.unstubAllGlobals());

  it('envía el access token y nunca lo guarda en localStorage', async () => {
    setAccessToken('tok-1');
    fetchMock.mockResolvedValueOnce(json(200, { ok: true }));
    await http.get('/api/v1/customers');
    const headers = fetchMock.mock.calls[0]![1]!.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer tok-1');
    expect(localStorage.length).toBe(0);
  });

  it('ante un 401 renueva la sesión una vez y repite la petición', async () => {
    setAccessToken('vencido');
    fetchMock
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' }))
      .mockResolvedValueOnce(json(200, { status: 'SIGNED_IN', accessToken: 'nuevo' }))
      .mockResolvedValueOnce(json(200, { items: [] }));
    const res = await http.get<{ items: unknown[] }>('/api/v1/customers');
    expect(res.items).toEqual([]);
    const refreshCall = fetchMock.mock.calls[1]!;
    expect(refreshCall[0]).toBe('/api/v1/auth/refresh');
    expect((refreshCall[1]!.headers as Record<string, string>)['X-Billing-Client']).toBe('web');
    expect((fetchMock.mock.calls[2]![1]!.headers as Record<string, string>).Authorization).toBe('Bearer nuevo');
  });

  it('si no se puede renovar, avisa que la sesión terminó', async () => {
    const expired = vi.fn();
    onSessionExpired(expired);
    fetchMock.mockResolvedValueOnce(json(401, { code: 'UNAUTHENTICATED' })).mockResolvedValueOnce(json(401, { code: 'INVALID_REFRESH_TOKEN' }));
    await expect(http.get('/api/v1/customers')).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledOnce();
  });

  it('varias peticiones simultáneas comparten una sola renovación', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise((r) => (resolve = r)));
    const a = refreshSession();
    const b = refreshSession();
    resolve(json(200, { status: 'SIGNED_IN', accessToken: 'x' }));
    expect(await a).toBe(true);
    expect(await b).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('convierte el error RFC 9457 en ApiError con código y campos', async () => {
    setAccessToken('t');
    fetchMock.mockResolvedValueOnce(
      json(400, { code: 'VALIDATION_FAILED', detail: 'Invalid request content.', fields: [{ field: 'legalName', message: 'must not be blank' }] }),
    );
    const err = await http.post('/api/v1/customers', {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe('VALIDATION_FAILED');
    expect((err as ApiError).fields[0]!.field).toBe('legalName');
  });
});
