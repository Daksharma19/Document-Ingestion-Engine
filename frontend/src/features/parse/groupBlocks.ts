import type { Block } from '@/api/types';

export type BlockGroup = { kind: 'single'; block: Block } | { kind: 'list'; items: Block[] };

/** Merges each run of consecutive LIST_ITEM blocks into one list group; everything else stays single. */
export function groupBlocks(blocks: Block[]): BlockGroup[] {
  const out: BlockGroup[] = [];
  for (const b of blocks) {
    const last = out[out.length - 1];
    if (b.type === 'LIST_ITEM') {
      if (last?.kind === 'list') last.items.push(b);
      else out.push({ kind: 'list', items: [b] });
    } else {
      out.push({ kind: 'single', block: b });
    }
  }
  return out;
}
