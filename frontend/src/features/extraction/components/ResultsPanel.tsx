import type { FileExtractionResult } from '@/api/types';
import { downloadJson } from '@/lib/format';
import type { ExtractionState } from '../hooks/useExtraction';
import { ResultCard } from './ResultCard';

function Summary({ results }: { results: FileExtractionResult[] }) {
  const ok = results.filter((r) => r.status === 'OK').length;
  const valid = results.filter((r) => r.valid).length;
  const cost = results.reduce((s, r) => s + r.costUnits, 0);
  return (
    <div className="summary">
      <Stat label="Files" value={results.length} />
      <Stat label="Extracted" value={`${ok}/${results.length}`} />
      <Stat label="Valid" value={`${valid}/${results.length}`} />
      <Stat label="Cost units" value={cost} />
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="stat">
      <div className="stat__value">{value}</div>
      <div className="stat__label">{label}</div>
    </div>
  );
}

export function ResultsPanel({ state }: { state: ExtractionState }) {
  switch (state.phase) {
    case 'idle':
      return (
        <div className="empty">
          <h2>No results yet</h2>
          <p className="muted">Add documents, pick a schema and run an extraction. Results show up here, one card per file.</p>
        </div>
      );
    case 'loading':
      return (
        <div className="empty" aria-live="polite">
          <div className="spinner" />
          <p className="muted">Extracting… OCR and vision tiers can take a while.</p>
        </div>
      );
    case 'error':
      return (
        <div className="alert alert--danger" role="alert">
          <strong>Request failed.</strong> {state.message}
        </div>
      );
    case 'success':
      return (
        <div className="stack">
          <div className="results-header">
            <Summary results={state.results} />
            <button type="button" className="btn btn--secondary" onClick={() => downloadJson('extraction-results.json', state.results)}>
              Download JSON
            </button>
          </div>
          {state.results.map((r, i) => (
            <ResultCard key={`${r.filename}-${i}`} result={r} />
          ))}
        </div>
      );
  }
}
