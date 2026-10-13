import { ExtractionForm } from './components/ExtractionForm';
import { ResultsPanel } from './components/ResultsPanel';
import { useExtraction } from './hooks/useExtraction';

export function ExtractionPage() {
  const { state, run, cancel } = useExtraction();
  return (
    <div className="layout">
      <ExtractionForm loading={state.phase === 'loading'} onSubmit={run} onCancel={cancel} />
      <div className="layout__results">
        <ResultsPanel state={state} />
      </div>
    </div>
  );
}
