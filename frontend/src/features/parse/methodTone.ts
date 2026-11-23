import type { PageMethod } from '@/api/types';
import type { BadgeTone } from '@/components/Badge';

export const METHOD_TONE: Record<PageMethod, BadgeTone> = {
  NATIVE: 'success',
  OCR: 'info',
  LAYOUT: 'warning',
  VISION: 'warning',
};

export const PENDING_TONE: BadgeTone = 'neutral';

export function confidenceTone(c: number | undefined): BadgeTone {
  if (c === undefined) return 'neutral';
  if (c >= 0.75) return 'success';
  if (c >= 0.5) return 'warning';
  return 'danger';
}
