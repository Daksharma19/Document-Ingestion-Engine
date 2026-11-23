interface HealthPillProps { status: 'checking' | 'up' | 'down' }

const LABEL = { checking: 'Checking API…', up: 'API online', down: 'API offline' } as const;

export function HealthPill({ status }: HealthPillProps) {
  return (
    <span className={`health health--${status}`} role="status" aria-live="polite">
      <span className="health__dot" aria-hidden="true" />
      <span className="health__label">{LABEL[status]}</span>
    </span>
  );
}
