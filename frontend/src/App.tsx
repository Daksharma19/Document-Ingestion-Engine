import { ExtractionPage } from '@/features/extraction/ExtractionPage';
import { useBackendHealth } from '@/features/extraction/hooks/useBackendHealth';

const HEALTH_LABEL = { checking: 'Checking backend…', up: 'Backend online', down: 'Backend offline' } as const;

export function App() {
  const health = useBackendHealth();
  return (
    <>
      <header className="topbar">
        <div className="topbar__brand">
          docextract <span className="muted">schema-driven document extraction</span>
        </div>
        <div className={`health health--${health}`}>
          <span className="health__dot" />
          {HEALTH_LABEL[health]}
        </div>
      </header>
      <main className="container">
        <ExtractionPage />
      </main>
    </>
  );
}
