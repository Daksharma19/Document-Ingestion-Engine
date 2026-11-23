import { useState } from 'react';
import type { ParsedDocument } from '@/api/types';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Card } from '@/components/Card';
import { CodeBlock } from '@/components/CodeBlock';
import { Segmented } from '@/components/Segmented';
import { formatDuration } from '@/lib/format';
import { MarkdownView } from './MarkdownView';
import { PageSection } from './PageSection';

type View = 'content' | 'markdown' | 'json';
const COLLAPSE_AFTER = 3;

export function ParsedDocumentCard({ doc }: { doc: ParsedDocument }) {
  const [view, setView] = useState<View>('content');
  const [showAll, setShowAll] = useState(false);
  const ok = doc.status === 'OK';
  const pages = doc.pages ?? [];
  const visible = showAll ? pages : pages.slice(0, COLLAPSE_AFTER);
  const hasMd = doc.markdown !== undefined;
  const current: View = view === 'markdown' && !hasMd ? 'content' : view;

  return (
    <Card
      as="article"
      title={
        <span className="result-title">
          {doc.filename ?? '(unnamed)'}
          <Badge tone={ok ? 'success' : 'danger'}>{doc.status}</Badge>
        </span>
      }
      actions={
        ok && (
          <Segmented<View>
            label={`View for ${doc.filename ?? 'document'}`}
            size="sm"
            value={current}
            onChange={setView}
            options={[
              { value: 'content', label: 'Content' },
              ...(hasMd ? [{ value: 'markdown' as const, label: 'Markdown' }] : []),
              { value: 'json', label: 'JSON' },
            ]}
          />
        )
      }
    >
      <dl className="meta">
        {doc.kind && <Meta k="Kind" v={doc.kind} />}
        {doc.mediaType && <Meta k="Type" v={doc.mediaType} />}
        <Meta k="Pages" v={String(pages.length)} />
        <Meta k="Cost" v={`${doc.costUnits} units`} />
        <Meta k="Time" v={formatDuration(doc.elapsedMs)} />
      </dl>

      {!ok && (
        <p className="error-text" role="alert">
          {doc.error ?? 'Parse failed.'}
        </p>
      )}

      {ok && current === 'content' && (
        <>
          {pages.length === 0 && <p className="muted">No text found in this document</p>}
          {visible.map((p, i) => (
            <PageSection key={`${p.index}-${i}`} page={p} />
          ))}
          {pages.length > COLLAPSE_AFTER && (
            <div className="page-toggle">
              <Button variant="ghost" size="sm" aria-expanded={showAll} onClick={() => setShowAll((s) => !s)}>
                {showAll ? `Show first ${COLLAPSE_AFTER} pages` : `Show all ${pages.length} pages`}
              </Button>
            </div>
          )}
        </>
      )}
      {ok && current === 'markdown' && doc.markdown !== undefined && (
        <MarkdownView markdown={doc.markdown} filename={doc.filename} />
      )}
      {ok && current === 'json' && <CodeBlock label="Parsed document JSON">{JSON.stringify(doc, null, 2)}</CodeBlock>}
    </Card>
  );
}

function Meta({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt>{k}</dt>
      <dd>{v}</dd>
    </div>
  );
}
