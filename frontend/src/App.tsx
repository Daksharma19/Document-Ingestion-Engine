import { Alert } from '@/components/Alert';
import { Segmented } from '@/components/Segmented';
import { ExtractionPage } from '@/features/extraction/ExtractionPage';
import { useExtraction } from '@/features/extraction/hooks/useExtraction';
import { ParsePage } from '@/features/parse/ParsePage';
import { useParse } from '@/features/parse/hooks/useParse';
import { useBackendHealth } from '@/hooks/useBackendHealth';
import { useMode, type Mode } from '@/hooks/useMode';

const MODES: { value: Mode; label: string }[] = [
  { value: 'extract', label: 'Extract' },
  { value: 'parse', label: 'Parse' },
];

export function App() {
  const health = useBackendHealth();
  const [mode, setMode] = useMode();
  // Both hooks live here so each mode keeps its state while the other is shown.
  const extraction = useExtraction();
  const parse = useParse();

  return (
    <>
      <header className="topbar">
        <div className="topbar__brand">
          docextract <span className="muted">document extraction and parsing</span>
        </div>
        <div className="topbar__mode">
          <Segmented<Mode> label="Mode" options={MODES} value={mode} onChange={setMode} />
        </div>
      </header>
      <main className="container">
        {health === 'down' && (
          <div className="stack">
            <Alert tone="warning" title="Backend offline">
              The API on :8080 is not answering /health. You can still submit; the request error will say what failed.
            </Alert>
          </div>
        )}
        <div hidden={mode !== 'extract'}>
          <ExtractionPage extraction={extraction} />
        </div>
        <div hidden={mode !== 'parse'}>
          <ParsePage parse={parse} />
        </div>
      </main>
    </>
  );
}
