import { useCallback, useRef, useState } from 'react';
import { ApiError, extract } from '@/api/client';
import type { ExtractRequest, FileExtractionResult } from '@/api/types';

export type ExtractionState =
  | { phase: 'idle' }
  | { phase: 'loading'; fileCount: number; startedAt: number }
  | { phase: 'success'; results: FileExtractionResult[] }
  | { phase: 'error'; message: string };

/**
 * Owns the request lifecycle: one in-flight request at a time, cancellable, stale responses dropped.
 * cancel() goes back to the previous success when there is one, otherwise to idle.
 */
export function useExtraction() {
  const [state, setState] = useState<ExtractionState>({ phase: 'idle' });
  const controller = useRef<AbortController | null>(null);
  const lastSuccess = useRef<ExtractionState | null>(null);

  const run = useCallback(async (req: ExtractRequest) => {
    controller.current?.abort();
    const ac = new AbortController();
    controller.current = ac;
    setState({ phase: 'loading', fileCount: req.files.length, startedAt: Date.now() });
    try {
      const results = await extract(req, ac.signal);
      if (ac.signal.aborted) return;
      const next: ExtractionState = { phase: 'success', results };
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
