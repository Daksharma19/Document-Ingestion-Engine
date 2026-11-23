import type { ReactNode } from 'react';
import type { BadgeTone } from './Badge';

interface StatProps {
  label: string;
  value: ReactNode;
  tone?: BadgeTone;
}

export function Stat({ label, value, tone = 'neutral' }: StatProps) {
  return (
    <div className={`stat stat--${tone}`}>
      <div className="stat__value">{value}</div>
      <div className="stat__label">{label}</div>
    </div>
  );
}
