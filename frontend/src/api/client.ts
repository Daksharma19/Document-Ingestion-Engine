import type { ExtractRequest, FileExtractionResult, ProblemDetail } from './types';

/** Base URL for the API. Empty in dev (Vite proxies to :8080); set VITE_API_BASE_URL for other deployments. */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

/** A request-level failure (bad schema, unparseable rule, network down). Per-file failures are NOT errors. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const problem = (await res.json()) as ProblemDetail;
    return new ApiError(problem.detail ?? problem.title ?? res.statusText, res.status);
  } catch {
    return new ApiError(`${res.status} ${res.statusText}`, res.status);
  }
}

export async function extract(req: ExtractRequest, signal?: AbortSignal): Promise<FileExtractionResult[]> {
  const form = new FormData();
  req.files.forEach((f) => form.append('files', f));
  form.append('schema', req.schema);
  if (req.rules?.trim()) form.append('rules', req.rules);
  form.append('tier', req.tier);
  form.append('includeText', String(req.includeText));

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}/api/extract`, { method: 'POST', body: form, signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError('Could not reach the backend. Is it running on :8080?');
  }
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as FileExtractionResult[];
}

export async function health(signal?: AbortSignal): Promise<boolean> {
  try {
    const res = await fetch(`${BASE_URL}/health`, { signal });
    return res.ok;
  } catch {
    return false;
  }
}
