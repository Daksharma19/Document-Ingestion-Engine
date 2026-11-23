import type { ExtractRequest, FileExtractionResult, ParseRequest, ParsedDocument, ProblemDetail, SchemaPreset } from './types';

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
  if (res.status === 413) return new ApiError('Upload too large (25 MB per request)', 413);
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

  return postForm<FileExtractionResult[]>('/api/extract', form, signal);
}

/** POST /api/parse: one ParsedDocument per file, in upload order. Per-file failures come back as status ERROR. */
export async function parse(req: ParseRequest, signal?: AbortSignal): Promise<ParsedDocument[]> {
  const form = new FormData();
  req.files.forEach((f) => form.append('files', f));
  // Options go in the query string, not as parts: Tomcat's multipart part-count limit (~50) counts every part,
  // so 50 files plus two option parts would 500 with FileCountLimitExceededException.
  const q = new URLSearchParams({ includeMarkdown: String(req.includeMarkdown), figures: String(req.figures) });
  return postForm<ParsedDocument[]>(`/api/parse?${q}`, form, signal);
}

/** Multipart POST. Never set Content-Type: the browser adds the boundary. AbortError passes through untouched. */
async function postForm<T>(path: string, form: FormData, signal?: AbortSignal): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, { method: 'POST', body: form, signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError('Could not reach the backend. Is it running on :8080?');
  }
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as T;
}

/** The server's built-in schemas; throws when the backend is unreachable (callers fall back to local presets). */
export async function listSchemas(signal?: AbortSignal): Promise<SchemaPreset[]> {
  const res = await fetch(`${BASE_URL}/api/schemas`, { signal });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as SchemaPreset[];
}

export async function health(signal?: AbortSignal): Promise<boolean> {
  try {
    const res = await fetch(`${BASE_URL}/health`, { signal });
    return res.ok;
  } catch {
    return false;
  }
}
