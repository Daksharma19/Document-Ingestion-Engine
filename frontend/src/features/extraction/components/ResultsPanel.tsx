import type { FileExtractionResult } from '@/api/types';
import { Alert } from '@/components/Alert';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { Stat } from '@/components/Stat';
import { useElapsed } from '@/hooks/useMode';
import { downloadJson } from '@/lib/format';
import type { ExtractionState } from '../hooks/useExtraction';
import { ResultCard } from './ResultCard';

function Summary({ results }: { results: FileExtractionResult[] }) {
  const ok = results.filter((r) => r.status === 'OK').length;
  const valid = results.filter((r) => r.valid).length;
  const cost = results.reduce((s, r) => s + r.costUnits, 0);
  const n = results.length;
  return (
    <div className="summary">
      <Stat label="Files" value={n} />
      <Stat label="Extracted" value={`${ok}/${n}`} tone={ok < n ? 'warning' : 'success'} />
      <Stat label="Valid" value={`${valid}/${n}`} tone={valid < n ? 'warning' : 'success'} />
      <Stat label="Cost units" value={cost} />
    </div>
  );
}

function Loading({ fileCount, startedAt }: { fileCount: number; startedAt: number }) {
  const secs = useElapsed(startedAt);
  return (
    <EmptyState title={`Extracting ${fileCount} ${fileCount === 1 ? 'file' : 'files'}… ${secs}s`} busy>
      OCR and vision tiers can take a while.
    </EmptyState>
  );
}

export function ResultsPanel({ state }: { state: ExtractionState }) {
  switch (state.phase) {
    case 'idle':
      return (
        <EmptyState title="No results yet" icon="docs">
          Add documents, pick a schema and run an extraction. Results show up here, one card per file.
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
            <Button variant="secondary" onClick={() => downloadJson('extraction-results.json', state.results)}>
              Download JSON
            </Button>
          </div>
          {state.results.map((r, i) => (
            <ResultCard key={`${r.filename}-${i}`} result={r} />
          ))}
        </div>
      );
  }
}
