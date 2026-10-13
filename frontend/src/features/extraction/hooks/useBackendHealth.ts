import { useEffect, useState } from 'react';
import { health } from '@/api/client';

export type HealthStatus = 'checking' | 'up' | 'down';

export function useBackendHealth(intervalMs = 15000): HealthStatus {
  const [status, setStatus] = useState<HealthStatus>('checking');
  useEffect(() => {
    const ac = new AbortController();
    const check = () =>
      health(ac.signal).then((ok) => {
        if (!ac.signal.aborted) setStatus(ok ? 'up' : 'down');
      });
    check();
    const id = setInterval(check, intervalMs);
    return () => {
      ac.abort();
      clearInterval(id);
    };
  }, [intervalMs]);
  return status;
}
