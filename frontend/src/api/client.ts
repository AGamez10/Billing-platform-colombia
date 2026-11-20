/**
 * Cliente HTTP de la API.
 *
 * - El access token vive solo en memoria (nunca en localStorage): un XSS no puede leerlo de
 *   un almacenamiento persistente, y al recargar la página se renueva con la cookie HttpOnly.
 * - Ante un 401 intenta UNA renovación (compartida entre peticiones simultáneas) y repite.
 * - Los errores llegan como RFC 9457 y se convierten en {@link ApiError} con `code` estable.
 */

export const CLIENT_HEADER = 'X-Billing-Client';
const CLIENT_NAME = 'web';

export interface FieldError {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: FieldError[];

  constructor(status: number, code: string, detail: string, fields: FieldError[] = []) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fields = fields;
  }
}

export interface SessionPayload {
  status: 'SIGNED_IN' | 'ORGANIZATION_REQUIRED';
  accessToken?: string;
  expiresIn?: number;
  organization?: { id: string; name: string | null; role: string };
  organizations?: { id: string; name: string; role: string }[];
}

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
let onExpired: () => void = () => {};

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function hasAccessToken(): boolean {
  return accessToken !== null;
}

/** La sesión no se pudo renovar: la interfaz vuelve al inicio de sesión. */
export function onSessionExpired(handler: () => void): void {
  onExpired = handler;
}

async function toError(res: Response): Promise<ApiError> {
  let body: { code?: string; detail?: string; fields?: FieldError[] } = {};
  try {
    body = await res.json();
  } catch {
    // Respuesta sin cuerpo JSON (proxy caído, HTML de error): se usa el estado HTTP.
  }
  const code = body.code ?? (res.status === 401 ? 'UNAUTHENTICATED' : `HTTP_${res.status}`);
  return new ApiError(res.status, code, body.detail ?? res.statusText, body.fields ?? []);
}

/** Renueva el access token con la cookie de refresh. Varias llamadas simultáneas comparten una sola renovación. */
export function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { [CLIENT_HEADER]: CLIENT_NAME },
        });
        if (!res.ok) {
          accessToken = null;
          return false;
        }
        const session = (await res.json()) as SessionPayload;
        accessToken = session.accessToken ?? null;
        return accessToken !== null;
      } catch {
        accessToken = null;
        return false;
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

async function send(method: Method, path: string, body: unknown, withAuth: boolean, extra: Record<string, string> = {}): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (path.startsWith('/api/v1/auth/')) headers[CLIENT_HEADER] = CLIENT_NAME;
  if (withAuth && accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export async function api<T>(method: Method, path: string, body?: unknown, extra: Record<string, string> = {}): Promise<T> {
  const isAuthEndpoint = path.startsWith('/api/v1/auth/') && !path.endsWith('/me');
  let res = await send(method, path, body, true, extra);
  if (res.status === 401 && !isAuthEndpoint) {
    if (await refreshSession()) {
      res = await send(method, path, body, true, extra);
    } else {
      onExpired();
    }
  }
  if (!res.ok) throw await toError(res);
  // 202/204 y respuestas sin cuerpo no traen JSON.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Petición con sesión que no es JSON (CSV de entrada o de salida), con la misma renovación ante 401. */
async function authorizedFetch(method: Method, path: string, init: { body?: string; contentType?: string; accept?: string }): Promise<Response> {
  const go = () =>
    fetch(path, {
      method,
      credentials: 'same-origin',
      headers: {
        Accept: init.accept ?? 'application/json',
        ...(init.contentType ? { 'Content-Type': init.contentType } : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      body: init.body,
    });
  let res = await go();
  if (res.status === 401) {
    if (await refreshSession()) res = await go();
    else onExpired();
  }
  if (!res.ok) throw await toError(res);
  return res;
}

export const http = {
  get: <T>(path: string) => api<T>('GET', path),
  post: <T>(path: string, body?: unknown) => api<T>('POST', path, body),
  /** POST con cabeceras extra, p. ej. Idempotency-Key en la venta rápida. */
  postWithHeaders: <T>(path: string, body: unknown, headers: Record<string, string>) => api<T>('POST', path, body, headers),
  put: <T>(path: string, body: unknown) => api<T>('PUT', path, body),
  patch: <T>(path: string, body: unknown) => api<T>('PATCH', path, body),
  del: (path: string) => api<void>('DELETE', path),
  /** Envía un archivo CSV como texto y recibe JSON. */
  postCsv: async <T>(path: string, csv: string): Promise<T> =>
    (await (await authorizedFetch('POST', path, { body: csv, contentType: 'text/csv; charset=utf-8' })).json()) as T,
  /** Descarga un archivo autenticado y lo guarda con el nombre dado. */
  download: async (path: string, filename: string): Promise<void> => {
    const res = await authorizedFetch('GET', path, { accept: 'text/csv' });
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};
