import { useCallback, useEffect, useRef, useState } from 'react';
import { http } from '../api/client';

export interface Resource<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  reload: () => Promise<void>;
}

/** GET con estado de carga y error. `path = null` no consulta (dependencia aún no lista). */
export function useApi<T>(path: string | null): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(path !== null);
  const current = useRef(path);
  current.current = path;

  const reload = useCallback(async () => {
    if (path === null) return;
    setLoading(true);
    try {
      const result = await http.get<T>(path);
      if (current.current === path) {
        setData(result);
        setError(null);
      }
    } catch (err) {
      if (current.current === path) setError(err);
    } finally {
      if (current.current === path) setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { data, error, loading, reload };
}
