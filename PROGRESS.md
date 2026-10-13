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
