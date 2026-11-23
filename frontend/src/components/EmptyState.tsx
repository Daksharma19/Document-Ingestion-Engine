import type { ReactNode } from 'react';
import { DocsIcon, ParseIcon } from './Icons';
import { Spinner } from './Spinner';

interface EmptyStateProps {
  title: string;
  children?: ReactNode;
  icon?: 'docs' | 'parse';
  busy?: boolean;
}

export function EmptyState({ title, children, icon = 'docs', busy = false }: EmptyStateProps) {
  return (
    <div className={`empty${busy ? ' empty--busy' : ''}`} aria-live="polite" aria-busy={busy || undefined}>
      <div className="empty__icon">
        {busy ? <Spinner /> : icon === 'parse' ? <ParseIcon /> : <DocsIcon />}
      </div>
      <p className="empty__title">{title}</p>
      {children && <div className="empty__body">{children}</div>}
    </div>
  );
}
