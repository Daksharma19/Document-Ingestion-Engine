# Progress

## Semester 1 foundation — done

| Step | What | Commit |
|---|---|---|
| 1 | Spring Boot 3.5 skeleton: `POST /api/extract`, `GET /health`, README | 16de11c |
| 2 | `ExtractionStrategy` interface + `CostTier`; `NativePdfTextStrategy` (PDFBox for PDF, Tika for other formats) | 6440772 |
| 3 | Naive keyword/regex `FieldMapper` (labels, dates, currency; `x-labels` hints) | 2800726 |
| 4 | `ValidationEngine`: JSON Schema (networknt) + pluggable business rules (line items sum, dates real and not future) | 50f7a7b |
| 5 | Labelled corpus format (`<name>.labels.json`), loader, 3 sample docs (PDF, DOCX, scanned PNG) | d7d7373 |
| 6 | Evaluation harness CLI: per-field/per-document accuracy, cost, console tables + CSV | df74a47 |

Tests: 50, all passing.

## Current baseline (native-pdf-text on 3-doc corpus)
- Field accuracy (micro): 50.0%
- Document accuracy (macro): 39.7%
- Total cost: 3 units
- invoice-001.pdf 86%, receipt-002.docx 33%, scanned-003.png 0%

## Known limitations
- Line items (arrays/objects) are not extracted by the baseline.
- Receipt: "Total Items: 3" is picked as the total (wrong value still passes business rules).
- Images / scans yield nothing: no OCR tier yet.
- Slash dates are parsed day-first (dd/MM/yyyy).
- Corpus is only 3 documents: enough to prove the harness, not to draw conclusions.

## Next (not started)
- More strategies: layout analysis, OCR (Tesseract via Tika), vision LLM.
- Router: cheapest tier first, escalate on validation failure.
- Grow the corpus; compare routed vs always-cheapest vs always-most-expensive.
