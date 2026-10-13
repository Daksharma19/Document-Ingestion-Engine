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
  costUnits: number;
  fields?: Record<string, unknown>;
  confidence?: Record<string, number>;
  fieldValidation?: Record<string, boolean>;
  valid?: boolean;
  violations?: Violation[];
  rules?: RuleResult[];
  error?: string;
  text?: string;
}

export interface ExtractRequest {
  files: File[];
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
