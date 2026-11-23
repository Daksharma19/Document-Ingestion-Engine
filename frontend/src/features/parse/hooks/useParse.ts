import { useCallback, useRef, useState } from 'react';
import { ApiError, parse } from '@/api/client';
import type { ParseRequest, ParsedDocument } from '@/api/types';

export type ParseState =
  | { phase: 'idle' }
  | { phase: 'loading'; fileCount: number; startedAt: number }
  | { phase: 'success'; results: ParsedDocument[] }
  | { phase: 'error'; message: string };

/**
 * One in-flight parse at a time: a new run aborts the old one, stale responses are dropped.
 * cancel() aborts the HTTP request (the server keeps working) and goes back to the previous success, if any.
 */
export function useParse() {
  const [state, setState] = useState<ParseState>({ phase: 'idle' });
  const controller = useRef<AbortController | null>(null);
  const lastSuccess = useRef<ParseState | null>(null);

  const run = useCallback(async (req: ParseRequest) => {
    controller.current?.abort();
    const ac = new AbortController();
    controller.current = ac;
    setState({ phase: 'loading', fileCount: req.files.length, startedAt: Date.now() });
    try {
      const results = await parse(req, ac.signal);
      if (ac.signal.aborted) return;
      const next: ParseState = { phase: 'success', results };
      lastSuccess.current = next;
      setState(next);
    } catch (e) {
      if ((e as Error).name === 'AbortError' || ac.signal.aborted) return;
      setState({ phase: 'error', message: e instanceof ApiError ? e.message : 'Unexpected error.' });
    }
  }, []);

  const cancel = useCallback(() => {
    controller.current?.abort();
    controller.current = null;
    setState(lastSuccess.current ?? { phase: 'idle' });
  }, []);

  return { state, run, cancel };
}
