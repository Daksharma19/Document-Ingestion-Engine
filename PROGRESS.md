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
- Line items (arrays/objects) are not extracted by the keyword mapper (the only remaining miss on the corpus).
- Slash dates are parsed day-first (dd/MM/yyyy).
- Cost units are relative tier weights, not measured money or latency.
- The vision tier does not escalate automatically; it must be requested with `tier=vision-llm`.
- The OCR language is English only (`eng.traineddata`).

## Next
- Set `OPENAI_API_KEY` and run `eval` to get real vision-tier numbers.
- Collect and label the real corpus (checklist), with a held-out split.
- Router escalation: cheapest tier first, escalate on validation failure.
- Line-item extraction (layout tier).
