import { ExtractionForm } from './components/ExtractionForm';
import { ResultsPanel } from './components/ResultsPanel';
import type { useExtraction } from './hooks/useExtraction';

/** State lives in App (useExtraction) so switching modes keeps results. */
export function ExtractionPage({ extraction }: { extraction: ReturnType<typeof useExtraction> }) {
  const { state, run, cancel } = extraction;
  return (
    <div className="layout">
      <div className="layout__form">
        <ExtractionForm loading={state.phase === 'loading'} onSubmit={run} onCancel={cancel} />
      </div>
      <div className="layout__results" aria-live="polite">
        <ResultsPanel state={state} />
      </div>
    </div>
  );
}
