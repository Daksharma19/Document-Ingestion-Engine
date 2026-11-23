import { ParseForm } from './components/ParseForm';
import { ParseResultsPanel } from './components/ParseResultsPanel';
import type { useParse } from './hooks/useParse';

/** State lives in App (useParse) so switching modes keeps results. */
export function ParsePage({ parse }: { parse: ReturnType<typeof useParse> }) {
  const { state, run, cancel } = parse;
  return (
    <div className="layout">
      <div className="layout__form">
        <ParseForm loading={state.phase === 'loading'} onSubmit={run} onCancel={cancel} />
      </div>
      <div className="layout__results" aria-live="polite">
        <ParseResultsPanel state={state} />
      </div>
    </div>
  );
}
