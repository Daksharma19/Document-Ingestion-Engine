// Starter schemas so a first-time user can extract something without writing JSON Schema by hand.

export interface Preset {
  id: string;
  label: string;
  schema: object;
  rules: string[];
}

export const PRESETS: Preset[] = [
  {
    id: 'invoice',
    label: 'Invoice',
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

export const pretty = (v: unknown) => JSON.stringify(v, null, 2);
