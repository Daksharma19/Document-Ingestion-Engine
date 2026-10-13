import { useCallback, useRef, useState } from 'react';
import { ApiError, extract } from '@/api/client';
import type { ExtractRequest, FileExtractionResult } from '@/api/types';

export type ExtractionState =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'success'; results: FileExtractionResult[] }
  | { phase: 'error'; message: string };

/** Owns the request lifecycle: one in-flight request at a time, cancellable, stale responses dropped. */
export function useExtraction() {
  const [state, setState] = useState<ExtractionState>({ phase: 'idle' });
  const controller = useRef<AbortController | null>(null);

  const run = useCallback(async (req: ExtractRequest) => {
    controller.current?.abort();
    const ac = new AbortController();
    controller.current = ac;
    setState({ phase: 'loading' });
    try {
      const results = await extract(req, ac.signal);
      if (!ac.signal.aborted) setState({ phase: 'success', results });
    } catch (e) {
      if ((e as Error).name === 'AbortError') return;
      setState({ phase: 'error', message: e instanceof ApiError ? e.message : 'Unexpected error.' });
    }
  }, []);

  const cancel = useCallback(() => {
    controller.current?.abort();
    setState({ phase: 'idle' });
  }, []);

  return { state, run, cancel };
}
