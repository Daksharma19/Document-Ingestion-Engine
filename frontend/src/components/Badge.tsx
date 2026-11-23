import type { ReactNode } from 'react';

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';

interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
  /** Shown as a native tooltip (e.g. the routing reason for a method badge). */
  title?: string;
}

export function Badge({ tone = 'neutral', children, title }: BadgeProps) {
  return (
    <span className={`badge badge--${tone}${title ? ' badge--hint' : ''}`} title={title}>
      {children}
    </span>
  );
}
