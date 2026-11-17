// Starter schemas so a first-time user can extract something without writing JSON Schema by hand.
// The full catalog comes from the backend (GET /api/schemas); these two are the offline fallback.

import { useEffect, useState } from 'react';
import { listSchemas } from '@/api/client';
import type { SchemaPreset } from '@/api/types';

export const AUTO_SCHEMA = 'auto';

export const FALLBACK_PRESETS: SchemaPreset[] = [
  {
    id: 'invoice',
    label: 'Invoice (basic)',
    category: 'Finance',
    description: 'Vendor, number, date, subtotal, tax and total.',
    keywords: [],
    schema: {
      type: 'object',
      required: ['invoiceNumber', 'total'],
      properties: {
        vendor: { type: 'string' },
        invoiceNumber: { type: 'string' },
        invoiceDate: { type: 'string', format: 'date' },
        subtotal: { type: 'number' },
        tax: { type: 'number' },
        total: { type: 'number', 'x-labels': ['Total Due'] },
      },
    },
    rules: ['subtotal + tax == total'],
  },
  {
    id: 'receipt',
    label: 'Receipt',
    category: 'Finance',
    description: 'Merchant, date and total.',
    keywords: [],
    schema: {
      type: 'object',
      required: ['total'],
      properties: {
        merchant: { type: 'string' },
        date: { type: 'string', format: 'date' },
        total: { type: 'number' },
      },
    },
    rules: [],
  },
];

/** Built-in schemas from the server, falling back to the local pair while it is unreachable. */
export function useSchemaPresets(): { presets: SchemaPreset[]; fromServer: boolean } {
  const [state, setState] = useState({ presets: FALLBACK_PRESETS, fromServer: false });
  useEffect(() => {
    const ctrl = new AbortController();
    listSchemas(ctrl.signal)
      .then((presets) => presets.length && setState({ presets, fromServer: true }))
      .catch(() => undefined);
    return () => ctrl.abort();
  }, []);
  return state;
}

/** Presets grouped by category, in catalog order. */
export function groupByCategory(presets: SchemaPreset[]): [string, SchemaPreset[]][] {
  const groups = new Map<string, SchemaPreset[]>();
  presets.forEach((p) => groups.set(p.category || 'Other', [...(groups.get(p.category || 'Other') ?? []), p]));
  return [...groups.entries()];
}

export const pretty = (v: unknown) => JSON.stringify(v, null, 2);
