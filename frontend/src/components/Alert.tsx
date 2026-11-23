import type { ReactNode } from 'react';
import { AlertIcon, CloseIcon, InfoIcon } from './Icons';

interface AlertProps {
  tone: 'danger' | 'warning' | 'info';
  title?: string;
  children: ReactNode;
  onDismiss?: () => void;
}

export function Alert({ tone, title, children, onDismiss }: AlertProps) {
  return (
    <div className={`alert alert--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <span className="alert__icon">{tone === 'info' ? <InfoIcon /> : <AlertIcon />}</span>
      <div className="alert__content">
        {title && <p className="alert__title">{title}</p>}
        <div className="alert__body">{children}</div>
      </div>
      {onDismiss && (
        <button type="button" className="alert__dismiss" aria-label="Dismiss" onClick={onDismiss}>
          <CloseIcon />
        </button>
      )}
    </div>
  );
}
