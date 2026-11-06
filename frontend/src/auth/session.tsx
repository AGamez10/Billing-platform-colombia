import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { CLIENT_HEADER, http, onSessionExpired, refreshSession, setAccessToken, type SessionPayload } from '../api/client';
import type { IdentificationType, Me, Role } from '../api/types';

export interface RegisterInput {
  organizationName: string;
  identificationType: IdentificationType;
  identificationNumber: string;
  checkDigit?: number;
  organizationEmail?: string;
  fullName: string;
  email: string;
  password: string;
}

type SessionState =
  | { status: 'loading' }
  | { status: 'signed-out' }
  | { status: 'signed-in'; me: Me };

interface SessionApi {
  state: SessionState;
  login(email: string, password: string, organizationId?: string): Promise<SessionPayload>;
  register(input: RegisterInput): Promise<void>;
  logout(): Promise<void>;
  can(permission: Permission): boolean;
}

/** Espejo de los permisos del backend, solo para ocultar botones. El backend decide siempre. */
export type Permission = 'write' | 'finalize' | 'manage' | 'provider' | 'usage';

const ROLE_PERMISSIONS: Record<Role, Permission[]> = {
  OWNER: ['write', 'finalize', 'manage', 'provider', 'usage'],
  ADMIN: ['write', 'finalize', 'manage', 'usage'],
  BILLER: ['write', 'finalize'],
  VIEWER: [],
};

const Ctx = createContext<SessionApi | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: 'loading' });

  const loadMe = useCallback(async () => {
    const me = await http.get<Me>('/api/v1/auth/me');
    setState({ status: 'signed-in', me });
  }, []);

  const adopt = useCallback(
    async (session: SessionPayload) => {
      if (session.status === 'SIGNED_IN' && session.accessToken) {
        setAccessToken(session.accessToken);
        await loadMe();
      }
    },
    [loadMe],
  );

  // Al abrir la app: si hay cookie de sesión vigente, se renueva el access token sin pedir clave.
  useEffect(() => {
    onSessionExpired(() => {
      setAccessToken(null);
      setState({ status: 'signed-out' });
    });
    (async () => {
      try {
        if (await refreshSession()) {
          await loadMe();
          return;
        }
      } catch {
        // sin sesión
      }
      setState({ status: 'signed-out' });
    })();
  }, [loadMe]);

  const api = useMemo<SessionApi>(
    () => ({
      state,
      async login(email, password, organizationId) {
        const session = await http.post<SessionPayload>('/api/v1/auth/login', { email, password, organizationId });
        await adopt(session);
        return session;
      },
      async register(input) {
        const session = await http.post<SessionPayload>('/api/v1/auth/register', input);
        await adopt(session);
      },
      async logout() {
        try {
          await fetch('/api/v1/auth/logout', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { [CLIENT_HEADER]: 'web' },
          });
        } finally {
          setAccessToken(null);
          setState({ status: 'signed-out' });
        }
      },
      can(permission) {
        return state.status === 'signed-in' && ROLE_PERMISSIONS[state.me.role].includes(permission);
      },
    }),
    [state, adopt],
  );

  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useSession(): SessionApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useSession fuera de SessionProvider');
  return ctx;
}
