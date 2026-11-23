import { useCallback, useEffect, useState } from 'react';

export type Mode = 'extract' | 'parse';

const read = (): Mode => (window.location.hash === '#parse' ? 'parse' : 'extract');

/** The active mode, backed by location.hash (#extract / #parse) so a reload keeps it. */
export function useMode(): [Mode, (m: Mode) => void] {
  const [mode, setModeState] = useState<Mode>(read);
  useEffect(() => {
    const onHash = () => setModeState(read());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  const setMode = useCallback((m: Mode) => {
    setModeState(m);
    if (window.location.hash !== `#${m}`) window.history.replaceState(null, '', `#${m}`);
  }, []);
  return [mode, setMode];
}

/** Whole seconds since startedAt, ticking every second while startedAt is set. */
export function useElapsed(startedAt: number | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (startedAt === undefined) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [startedAt]);
  return startedAt === undefined ? 0 : Math.max(0, Math.floor((now - startedAt) / 1000));
}
