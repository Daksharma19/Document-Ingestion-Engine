import type { ReactNode } from 'react';

interface CardProps {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  as?: 'section' | 'article';
  id?: string;
}

export function Card({ title, actions, children, as: Tag = 'section', id }: CardProps) {
  return (
    <Tag className="card" id={id}>
      {(title || actions) && (
        <header className="card__header">
          {title && <h2 className="card__title">{title}</h2>}
          {actions && <div className="card__actions">{actions}</div>}
        </header>
      )}
      <div className="card__body">{children}</div>
    </Tag>
  );
}
