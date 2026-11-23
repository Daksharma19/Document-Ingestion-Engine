import type { PageMethod, ParsedDocument } from '@/api/types';
import { Alert } from '@/components/Alert';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { Stat } from '@/components/Stat';
import { useElapsed } from '@/hooks/useMode';
import { downloadJson, formatDuration } from '@/lib/format';
import type { ParseState } from '../hooks/useParse';
import { ParsedDocumentCard } from './ParsedDocumentCard';

const METHODS: PageMethod[] = ['NATIVE', 'OCR', 'LAYOUT', 'VISION'];

function Summary({ results }: { results: ParsedDocument[] }) {
  const ok = results.filter((r) => r.status === 'OK').length;
  const errors = results.length - ok;
  const pages = results.flatMap((r) => r.pages ?? []);
  const pending = pages.filter((p) => p.pending).length;
  const cost = results.reduce((s, r) => s + r.costUnits, 0);
  const time = results.reduce((m, r) => Math.max(m, r.elapsedMs), 0);
  const byMethod = METHODS.map((m) => [m, pages.filter((p) => p.method === m && !p.pending).length] as const).filter(
    ([, n]) => n > 0,
  );
  return (
    <div className="summary">
      <Stat label="Files" value={results.length} />
      <Stat label="OK" value={`${ok}/${results.length}`} tone={errors ? 'warning' : 'success'} />
      {errors > 0 && <Stat label="Errors" value={errors} tone="danger" />}
      <Stat label="Pages" value={pages.length} />
      {byMethod.map(([m, n]) => (
        <Stat key={m} label={`${m} pages`} value={n} />
      ))}
      {pending > 0 && <Stat label="Pending pages" value={pending} tone="warning" />}
      <Stat label="Cost units" value={cost} />
      <Stat label="Slowest file" value={formatDuration(time)} />
    </div>
  );
}

function Loading({ fileCount, startedAt }: { fileCount: number; startedAt: number }) {
  const secs = useElapsed(startedAt);
  return (
    <EmptyState title={`Parsing ${fileCount} ${fileCount === 1 ? 'file' : 'files'}… ${secs}s`} icon="parse" busy>
      Scanned pages go through OCR and can take a while.
    </EmptyState>
  );
}

export function ParseResultsPanel({ state }: { state: ParseState }) {
  switch (state.phase) {
    case 'idle':
      return (
        <EmptyState title="Nothing parsed yet" icon="parse">
          Add documents and parse them. Each page shows how it was read and why, its content, and the Markdown.
        </EmptyState>
      );
    case 'loading':
      return <Loading fileCount={state.fileCount} startedAt={state.startedAt} />;
    case 'error':
      return (
        <Alert tone="danger" title="Request failed">
          {state.message}
        </Alert>
      );
    case 'success':
      return (
        <div className="stack">
          <div className="results-header">
            <Summary results={state.results} />
            <Button variant="secondary" onClick={() => downloadJson('parse-results.json', state.results)}>
              Download all JSON
            </Button>
          </div>
          {state.results.map((d, i) => (
            <ParsedDocumentCard key={`${d.filename}-${i}`} doc={d} />
          ))}
        </div>
      );
  }
}
