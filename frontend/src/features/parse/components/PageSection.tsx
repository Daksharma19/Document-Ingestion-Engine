import type { ParsedPage } from '@/api/types';
import { Badge } from '@/components/Badge';
import { formatPercent } from '@/lib/format';
import { groupBlocks } from '../groupBlocks';
import { METHOD_TONE, PENDING_TONE, confidenceTone } from '../methodTone';
import { BlockView } from './BlockView';

export function PageSection({ page }: { page: ParsedPage }) {
  const blocks = page.blocks ?? [];
  return (
    <section className={`page${page.pending ? ' page--pending' : ''}`} aria-label={`Page ${page.index}`}>
      <header className="page__header">
        <strong>Page {page.index}</strong>
        <Badge tone={METHOD_TONE[page.method]} title={page.reason ?? 'How this page was read'}>
          {page.method}
        </Badge>
        {page.confidence !== undefined && (
          <Badge tone={confidenceTone(page.confidence)} title="Page confidence">
            {formatPercent(page.confidence)}
          </Badge>
        )}
        {page.pending && <Badge tone={PENDING_TONE}>pending</Badge>}
        {page.reason && <span className="page__reason muted">{page.reason}</span>}
      </header>
      {page.pending ? (
        <p className="muted">Not parsed: {page.reason ?? 'method not available'}</p>
      ) : blocks.length === 0 ? (
        <p className="muted">No text found on this page</p>
      ) : (
        <div className="blocks">
          {groupBlocks(blocks).map((g, i) => (
            <BlockView key={i} group={g} />
          ))}
        </div>
      )}
    </section>
  );
}
