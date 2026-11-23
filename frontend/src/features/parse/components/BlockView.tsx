import type { Block } from '@/api/types';
import { DataTable } from '@/components/DataTable';
import type { BlockGroup } from '../groupBlocks';

/** level 1 -> h3, 2 -> h4, 3+ -> h5 (h6 for anything deeper than 3), so parsed content never breaks the page outline. */
function headingTag(level: number | undefined): 'h3' | 'h4' | 'h5' | 'h6' {
  const l = level ?? 1;
  if (l <= 1) return 'h3';
  if (l === 2) return 'h4';
  if (l === 3) return 'h5';
  return 'h6';
}

function SingleBlock({ block }: { block: Block }) {
  switch (block.type) {
    case 'HEADING': {
      const H = headingTag(block.level);
      return <H className="block block--heading">{block.text}</H>;
    }
    case 'PARAGRAPH':
      return <p className="block block--paragraph">{block.text}</p>;
    case 'LIST_ITEM':
      return (
        <ul className="block block--list">
          <li>{block.text}</li>
        </ul>
      );
    case 'TABLE': {
      const rows = block.rows ?? [];
      if (rows.length === 0) return <p className="block block--paragraph">{block.text}</p>;
      const [head, ...body] = rows;
      return (
        <div className="block block--table">
          <DataTable head={head} rows={body} />
        </div>
      );
    }
    case 'FIGURE':
      return (
        <figure className="block block--figure">
          <figcaption className="muted">Text in figure</figcaption>
          <p>{block.text}</p>
        </figure>
      );
  }
}

export function BlockView({ group }: { group: BlockGroup }) {
  if (group.kind === 'list') {
    return (
      <ul className="block block--list">
        {group.items.map((b, i) => (
          <li key={i}>{b.text}</li>
        ))}
      </ul>
    );
  }
  return <SingleBlock block={group.block} />;
}
