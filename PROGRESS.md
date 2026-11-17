# Progress

## Semester 1 foundation (done)

| Step | What | Commit |
|---|---|---|
| 1 | Spring Boot 3.5 skeleton: `POST /api/extract`, `GET /health`, README | 16de11c |
| 2 | `ExtractionStrategy` interface + `CostTier`; native text strategy (PDFBox for PDF, Tika for other formats) | 6440772 |
| 3 | Naive keyword/regex `FieldMapper` (labels, dates, currency; `x-labels` hints) | 2800726 |
| 4 | `ValidationEngine`: JSON Schema (networknt) + pluggable business rules | 50f7a7b |
| 5 | Labelled corpus format, loader, 3 sample docs | d7d7373 |
| 6 | Evaluation harness CLI | df74a47 |

## Phase 2 (branch `phase-2`, not merged)

1. **Multi-file API:** `files[]` + one schema (+ optional `rules`, `tier`, `includeText`) give one result per file. Per-file status: OK / TIER_NOT_CONFIGURED / UNSUPPORTED / ERROR.
2. **Type detection + routing:** Tika detects the type from the bytes. PDFs with a text layer go to native text, scans and images to OCR, Office/text formats to Tika.
3. **OCR tier:** Tess4J 5.20 (bundled native Tesseract), 300 DPI rendering, median filter + Otsu preprocessing.
4. **Vision-LLM tier (OpenAI):** fully implemented (page images + schema, JSON mode, reply projected onto the schema). **Not configured**: no `OPENAI_API_KEY` was set, so it returns `TIER_NOT_CONFIGURED`. Verified only against a local stub server, never against the real API.
5. **Validation:** format assertions on; null treated as absent; declarative rule expressions (`x-rules` or the request); per-field `fieldValidation` booleans; per-rule PASSED/FAILED/SKIPPED.
6. **Harness:** all tiers + `auto-routed`; skips unconfigured tiers and unreadable documents instead of scoring them 0; tier comparison table + `tiers.csv`.
7. **Corpus checklist:** [docs/CORPUS_CHECKLIST.md](docs/CORPUS_CHECKLIST.md).

Tests: 89, all passing (including real Tesseract OCR on both scanned samples).

## Current numbers (4 synthetic documents, run 2026-10-13)

| strategy | docs run | field acc | doc acc | cost/doc |
|---|---|---|---|---|
| native-text | 4/4 | 45.0% | 46.4% | 1.00 |
| ocr | 3/4 (DOCX skipped) | 94.1% | 95.2% | 20.00 |
| auto-routed | 4/4 | 95.0% | 96.4% | 10.50 |
| vision-llm | not configured | - | - | - |

**These numbers are not results.** The corpus is 4 synthetic documents, and the mapper heuristics were tuned
while looking at them (column-gap trimming, digit-group rejoining, longer-label skip, vendor-line fallback).
They show that the pipeline and harness work end to end, nothing more.

## Known limitations
- Line items come from a generic text-table heuristic (header row → numeric trailing columns → summary line); unusual layouts (descriptions after the numbers, wrapped columns) will still miss.
- Slash dates are parsed day-first (dd/MM/yyyy).
- Cost units are relative tier weights, not measured money or latency.
- The vision tier does not escalate automatically; it must be requested with `tier=vision-llm`.
- The OCR language is English only (`eng.traineddata`).

## Next
- Set `OPENAI_API_KEY` and run `eval` to get real vision-tier numbers.
- Collect and label the real corpus (checklist), with a held-out split.
- Router escalation: cheapest tier first, escalate on validation failure.
- Line-item extraction (layout tier).

## Generic invoice mapping (after the phase 2 commit)
- Built-in invoice vocabulary: the concept is recognised from the field name (`grandTotal`, `amount_due`, `billNo`, ...), with prioritised label synonyms (Amount Payable > Grand Total > Total, ...).
- Dates in any numeric separator (`28-09-2026`, `14.02.25`, `2025/02/14`), ordinals, `12-Mar-2024`; Indian lakh grouping; `%` rates are not amounts.
- Currency detection; tax-summary rows (CGST+SGST, VAT bands) summed when tax is not labelled.
- Generic line-item table reader.
- Fix: a label at the end of a line no longer takes its value from the next line.
- Checked on one real invoice (an Indian POS tax invoice; not committed). Native tier: every extracted field matches the document; subtotal is not printed as a labelled value, so it stays null. The OCR tier on the same file is noticeably worse (vendor from logo, invoice number lost, ₹ read as £).
- Tests: 95 passing; new tests use US, EU-VAT and Indian-GST invoice texts.

## Generic engine, tested on real documents (2026-10-13)
Real set: `test-invoices/` (4 real invoices, not committed; labels hand-written from the printed documents).
Three of them were unseen when this work started; their native-text field accuracy was 71% / 33% / 78%.
Every fix was general (no template rules):
- PDF text keeps column structure: wide horizontal gaps become column breaks.
- Layout normaliser: header row over value row ("DUE DATE" over "11/12/2026") and block labels ("From:" over the name) become "Label: value".
- Numeric date order inferred per document (10/13/2026 proves month-first).
- Numbers directly after their label are preferred ("TOTAL $313.90" over "TOTAL DISCOUNT $0.40").
- Line items: header row fixes column order (QTY-first or ITEM-first), wrapped rows are joined, the header yielding the most rows wins.
- Vendor: ignores document titles, stops at the recipient block.
- Schema-driven, domain-free fallbacks: `pattern`, `enum`, `format: email/uri`; labelled values must satisfy pattern/enum.
- Invoice knowledge no longer leaks: only the document's own date is guessed; `customerId`/`deliveryDate` use their own labels or stay null.

Result on the real set: native-text 100% field accuracy (4/4 exact); OCR tier on the same PDFs 65.6% (rendering + Tesseract
loses logos/₹/small text). Non-invoice schema (salesperson, shipping method, terms, phone via pattern, email via format,
ship mode via enum, order id, account no., delivery date) on 3 PDFs: 30/30 fields correct, including correct nulls.
Caveat: the fixes were developed while looking at these 4 documents, so 100% is an upper bound, not a held-out result.
Frontend checked end to end in the browser: 7 mixed files (4 real PDFs, scanned PDF, PNG, DOCX), vision tier
not-configured cards, malformed-rule error. Tests: 99 passing.

## test-docs: first non-invoice document (2026-10-13)
`test-docs/` = the 4 invoices + a real résumé (not committed; labels hand-written from the documents).
Résumé baseline with no résumé-specific code: 70% (name, summary paragraph and bullet list missed).
General fixes: `SectionExtractor` (text under a heading → paragraph for strings, bullets for string arrays,
hyphenated line breaks re-joined); title-line fallback for subject-name fields (`name`, `fullName`, `candidateName`...);
text labels need a visible separator (no "Next visit in March" false positives); unlabelled totals only from
money-formatted amounts (no phone numbers). Result: test-docs native/auto 100% (5/5 exact), synthetic corpus unchanged,
104 tests passing. Frontend re-checked on :5174 with the résumé schema and the invoice preset.

## OCR improvements (2026-10-13)
Measured on all 9 labelled documents forced through OCR (5 real in test-docs + 4 synthetic). Every change was
kept only if the matrix improved; numbers are field accuracy.
- Baseline (PSM 6, median+Otsu on every page): 67.8%. Cleaning clean pages destroyed them (Haldiram 86% -> 29%).
- Noise-gated cleaning: speckle detector (share of speck-sized blobs; clean pages <= 0.011, photocopy 0.95, threshold 0.25).
- Layout clean: invert wide solid dark bars (white-on-dark captions), erase long ruling lines (bordered cells).
- OCR text clean-up: lone "|" from short borders removed, "JavaScript /TypeScript" re-joined.
- Bullets misread by OCR ("e NPTEL") recognised when the same marker starts 2+ lines.
- Tried and rejected: PSM 3 (worse), 400 DPI (no gain, 2x slower), tessdata_best (+~2 points, 5x slower).
Result: 93.2% (from 67.8%); test-docs OCR 54.8% -> 90.5%. Native/auto unchanged (100%). 108 tests.
Still wrong under OCR: character confusions with no signal in the text ("+91"->"491", "19"->"l9",
small bold "SuperStore"->"peers") and one line-order ambiguity (Wordpress item name vs its sub-line).
Vision tier: key not yet provided to the server; `.tools/secrets.properties` (git-ignored) is now read for it.

## Python OCR, more schemas, engine work (branch `engine-python-ocr`, 2026-10-17)
**OCR moved to Python** (`ocr-service/`, FastAPI + RapidOCR/PP-OCRv6 on ONNX Runtime + OpenCV); Java only routes.
Tess4J stays as the automatic fallback when the service is down (`docextract.ocr.engine=auto`).
- Layout rebuilt from boxes: lines grouped by vertical overlap, wide gaps written as 3 spaces (same contract as the
  PDF text layer), blank lines at paragraph gaps. Skew is measured from the boxes and corrected geometrically.
- Speed: detection runs on a 1600 px copy, recognition on full-resolution crops (~10 s → ~3.5 s per A4 page);
  non-local-means denoise dropped (7 s/page, no gain). The 180° line classifier is **off**: it flipped dense
  justified résumé lines into garbage.
- OCR tier vs native text on the 5 real test-docs (native = reference, `invoice-detailed` / `resume` schemas):
  **Tesseract 82.4% → PP-OCR 96.2%** field agreement; résumé 5/10 → 10/10; synthetic scans 10/10 on both.
  Remaining differences: Haldiram vendor read from the logo ("Haldiram's"), Wordpress item description takes its sub-line.

**15 built-in schemas** served at `GET /api/schemas`; `schema` may be an id or `auto` (keyword classifier).
**Engine:** nested objects, booleans, name-implied value shapes (email/phone/GSTIN/IFSC/PAN/IBAN/…), receiving-party
guard, multi-line addresses, bank-statement tables (debit vs credit from the balance change), payslip/statement
totals no longer read as invoice totals, block labels skip lines that carry their own labels (SuperStore
"Bill To" now gives "Sean Miller", was "Standard Class"), nested nulls count as absent in validation.
**Routing:** escalation native → OCR on weak results (keeps the better one; vision only when enabled).
Frontend: presets from the server, grouped, plus "Auto-detect"; results show detected type and OCR engine;
nested fields shown as `vendor.name` rows. Tests: 124 Java + 2 Python, all passing.
Caveat: bank-statement/payslip/utility/ID/insurance schemas are tested on synthetic text only; no real samples yet.

## Speed as the USP (2026-10-17)
Profiled first: OCR recognition was ~90% of OCR time (95% of it inside the ONNX model), detection and rendering small.
- **Recogniser:** output layer of PP-OCRv6 small cut to Latin script (18,710 -> 419 classes, `ocr-service/trim_model.py`,
  built on first start): identical text, ~17-34% less OCR time. Long lines split at word gaps (attention cost grows
  faster than line length; a first version cut at letter gaps and broke words, fixed: gaps >= 0.35 x height).
  Four CPU recognisers in parallel (ONNX Runtime kept only ~50% of cores busy). cuDNN search HEURISTIC, not EXHAUSTIVE.
- **Service:** detection lock separate from the recogniser pool; pages/documents overlap; admission limit of 2.
- **Java:** content-hash cache of read text (repeat uploads ~20-150 ms), PDF parsed once, files of a batch in parallel,
  startup warm-up, `elapsedMs` per result, OCR service URL 127.0.0.1 (localhost cost ~0.2-1 s per call on Windows).
- **Bugs found on the way:** PDFium is not thread-safe (concurrent renders dropped glyphs and once crashed the
  service: now behind one lock); a busy OCR service failed the 2 s health check and files silently went to
  Tesseract (a timeout now means busy, a successful call refreshes health, cache hits skip the check); classifier
  ties between invoice types (keywords now weighted by how many types share them, title lines count most).
- Numbers: see README "Speed". Accuracy unchanged (96.2% OCR agreement, 10/10 labelled scans, all samples valid).
  Rejected: v6 tiny, English v4/v5 models, int8 quantisation, lower DPI. Tests: 131 Java + 5 Python.
