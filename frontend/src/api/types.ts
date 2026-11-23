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

// ---- /api/parse: com.docextract.parse.model.* (all @JsonInclude(NON_NULL), so nullable fields are optional) ----

export type PageMethod = 'NATIVE' | 'OCR' | 'LAYOUT' | 'VISION';
export type BlockType = 'HEADING' | 'PARAGRAPH' | 'LIST_ITEM' | 'TABLE' | 'FIGURE';

/** PDF points, origin top-left. */
export interface BBox {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface Block {
  type: BlockType;
  text?: string;
  /** Headings only; 1 = top. */
  level?: number;
  /** Tables only; rows[0] is the header when the source had one. */
  rows?: string[][];
  /** Absent when the source has no geometry (DOCX). */
  bbox?: BBox;
  confidence: number;
  method?: PageMethod;
}

export interface PageProbe {
  width: number;
  height: number;
  meaningfulChars: number;
  lines: number;
  garbageRatio: number;
  imageAreaRatio: number;
  inkRatio?: number;
  functionWordRatio?: number;
}

export interface ParsedPage {
  /** 1-based (PdfPage.index is 1-based; OFFICE_OR_TEXT emits 1). */
  index: number;
  method: PageMethod;
  reason?: string;
  pending: boolean;
  confidence?: number;
  costUnits: number;
  probe?: PageProbe;
  blocks?: Block[];
}

export type ParseStatus = 'OK' | 'ERROR';

export interface ParsedDocument {
  filename?: string;
  mediaType?: string;
  /** Free string on the wire; display verbatim. */
  kind?: string;
  status: ParseStatus;
  error?: string;
  pages?: ParsedPage[];
  markdown?: string;
  costUnits: number;
  elapsedMs: number;
}

export interface ParseRequest {
  files: File[];
  includeMarkdown: boolean;
  figures: boolean;
}
