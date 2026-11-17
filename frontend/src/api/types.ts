// Mirrors the backend contract: com.docextract.api.FileExtractionResult and friends.
// Keep in sync with the Java records; these are the only shapes the UI trusts from the wire.

export type Tier = 'auto' | 'native-text' | 'ocr' | 'vision-llm';

export type FileStatus = 'OK' | 'TIER_NOT_CONFIGURED' | 'UNSUPPORTED' | 'ERROR';

export type RuleOutcome = 'PASSED' | 'FAILED' | 'SKIPPED';

export interface Violation {
  field: string | null;
  rule: string;
  message: string;
  relatedFields: string[];
}

export interface RuleResult {
  expression: string;
  outcome: RuleOutcome;
  detail: string;
  fields: string[];
}

export interface FileExtractionResult {
  filename: string;
  detectedType?: string;
  documentKind?: string;
  status: FileStatus;
  tier?: string;
  strategy?: string;
  routing?: string;
  /** What read the document inside the tier, e.g. "rapidocr-ppocr" or "tesseract" for OCR. */
  engine?: string;
  /** The built-in schema used (schema sent as an id, or "auto"). */
  schemaId?: string;
  /** With schema "auto": the document type the classifier picked. */
  documentType?: string;
  costUnits: number;
  /** Server-side wall-clock time for this file, in milliseconds. */
  elapsedMs?: number;
  fields?: Record<string, unknown>;
  confidence?: Record<string, number>;
  fieldValidation?: Record<string, boolean>;
  valid?: boolean;
  violations?: Violation[];
  rules?: RuleResult[];
  error?: string;
  text?: string;
}

/** A built-in schema from GET /api/schemas (com.docextract.schema.SchemaCatalog.SchemaPreset). */
export interface SchemaPreset {
  id: string;
  label: string;
  category: string;
  description: string;
  keywords: string[];
  rules: string[];
  schema: object;
}

export interface ExtractRequest {
  files: File[];
  /** JSON Schema text, a built-in schema id, or "auto". */
  schema: string;
  rules?: string;
  tier: Tier;
  includeText: boolean;
}

/** RFC 7807 problem detail, returned by the backend for request-level (4xx) errors. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}
