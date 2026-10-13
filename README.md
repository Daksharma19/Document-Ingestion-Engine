# docextract — schema-driven document extraction

POST one or more documents (PDF, DOCX, image, spreadsheet, …) plus a JSON Schema. For each document you get
back JSON matching the schema, a confidence score per field, a validation pass/fail per field, and the list
of violations.

**Status (phase 2):** three working tiers (native text, OCR, plus the vision LLM, which works once an API key
is set), automatic routing by file type, a validation engine with declarative business rules, and an evaluation
harness that compares tiers on accuracy and cost. See [PROGRESS.md](PROGRESS.md).

## Requirements
- Java 21 (Maven is bundled via `./mvnw`)
- OCR: Tesseract language data `eng.traineddata` in `.tools/tessdata/` (or set `TESSDATA_PREFIX`). Tess4J
  bundles the native Tesseract library for Windows x64; on Linux/macOS install `tesseract` (5.x) from your package
  manager. Without the data file the OCR tier reports `TIER_NOT_CONFIGURED`.
  ```bash
  mkdir -p .tools/tessdata && curl -L -o .tools/tessdata/eng.traineddata https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata
  ```
- Vision tier (optional): `OPENAI_API_KEY` in the environment. Without it the tier reports
  `TIER_NOT_CONFIGURED` and never returns made-up output.

## Run the API
```bash
./mvnw spring-boot:run
# or
./mvnw package && java -jar target/docextract-0.0.1-SNAPSHOT.jar
```
Tests: `./mvnw test`

### GET /health
```bash
curl localhost:8080/health
# {"status":"UP"}
```

### POST /api/extract
Multipart form:

| part | required | meaning |
|---|---|---|
| `files` | yes, 1–20 | the documents (repeat the part). A single legacy `file` part also works. |
| `schema` | yes | JSON Schema text describing the fields wanted |
| `rules` | no | JSON array of business-rule expressions, e.g. `["subtotal + tax == total"]` |
| `tier` | no | `auto` (default), `native-text`, `ocr` or `vision-llm` |
| `includeText` | no | `true` echoes the text the tier read (useful for debugging OCR) |

```bash
curl -F files=@corpus/invoice-001.pdf -F files=@corpus/scanned-004.pdf \
     -F 'schema={"type":"object","required":["invoiceNumber","total"],"properties":{"vendor":{"type":"string"},"invoiceNumber":{"type":"string"},"invoiceDate":{"type":"string","format":"date"},"subtotal":{"type":"number"},"tax":{"type":"number"},"total":{"type":"number","x-labels":["Total Due"]}}}' \
     -F 'rules=["subtotal + tax == total"]' \
     localhost:8080/api/extract
```
Response: an array with one entry per file, in upload order (abridged):
```json
[
  { "filename": "invoice-001.pdf", "detectedType": "application/pdf", "documentKind": "PDF",
    "status": "OK", "tier": "NATIVE_TEXT", "strategy": "native-text", "routing": "PDF has a text layer",
    "costUnits": 1,
    "fields": { "vendor": "ACME Supplies Ltd", "invoiceNumber": "INV-2024-001", "invoiceDate": "2024-03-12",
                "subtotal": 140.00, "tax": 25.20, "total": 165.20 },
    "confidence": { "vendor": 0.8, "invoiceNumber": 0.8, "...": 0.8 },
    "fieldValidation": { "vendor": true, "invoiceNumber": true, "...": true },
    "valid": true, "violations": [],
    "rules": [ { "expression": "subtotal + tax == total", "outcome": "PASSED", "detail": "", "fields": ["subtotal","tax","total"] } ] },
  { "filename": "scanned-004.pdf", "status": "OK", "tier": "OCR",
    "routing": "PDF has no usable text layer (scanned/image-only)", "costUnits": 20, "...": "..." }
]
```
`status` is per file: `OK`, `TIER_NOT_CONFIGURED`, `UNSUPPORTED` (e.g. OCR on a DOCX) or `ERROR`. One bad
file never fails the whole batch. Request-level problems (no files, invalid schema JSON, a rule that does not
parse, an unknown tier) return 400 as RFC 7807 problem JSON.

### Routing (`tier=auto`)
The type is detected from the bytes with Tika, not from the client's filename or content type.

| detected | route |
|---|---|
| PDF with a text layer (≥ 20 letters/digits per page) | native text (PDFBox) |
| PDF without one (scan) | OCR |
| image (PNG/JPEG/TIFF/…) | OCR |
| DOCX, XLSX, HTML, text, … | native text (Tika) |

The vision tier is used only when requested. Escalating to it on validation failure is future work.

### Tiers
| strategy | tier | cost units | how |
|---|---|---|---|
| `native-text` | NATIVE_TEXT | 1 | PDFBox text layer / Tika, then the keyword-regex field mapper |
| `ocr` | OCR | 20 | PDF pages rendered at 300 DPI (or the image), median-filtered and Otsu-binarised, Tesseract via Tess4J, then the same mapper |
| `vision-llm` | VISION_LLM | 100 | pages rendered at 150 DPI (max 3) + the schema sent to OpenAI Chat Completions in JSON mode; the reply is projected onto the schema's properties |

Cost units are relative (from the phase 1 tier design), not money. Vision settings live in
`application.properties` (`docextract.vision.model`, default `gpt-4o-mini`).

### Schema hints the mapper understands
`title` and `x-labels` (extra label text to search for), e.g.
`"grandTotal": {"type": "number", "x-labels": ["Amount Payable"]}`.

## Validation
Every tier's output goes through the same engine:
1. **JSON Schema** (networknt, draft 2020-12 by default): `required`, types, `format` (asserted, so
   `"2024-02-30"` fails `format: date`), ranges, and so on. A field the tier did not find (null) counts as
   *absent*: it fails only if it is `required`.
2. **Built-in rules** (Spring beans): dates must be real and not in the future; line items must reconcile with
   the totals.
3. **Declarative rules**, from the schema's `"x-rules": [...]` and/or the request's `rules` part.

`fieldValidation` is `true` for a field unless some violation implicates it. A failing cross-field rule
implicates every field it mentions.

### Rule expressions
```
sum(lineItems[].amount) == subtotal
subtotal + tax == total
dueDate >= invoiceDate                    # ISO dates compare as strings
total > 0 && count(lineItems[]) >= 1
!exists(discount) || discount <= subtotal
round(avg(lineItems[].amount), 2) < 1000
```
- Paths: `field`, `a.b`, `items[]` (every element), `items[0]`
- Operators: `+ - * /`, `== != < <= > >=`, `&& || !`, parentheses
- Functions: `sum count min max avg` (over lists), `abs`, `round(x[, places])`, `exists(path)`
- Number `==` allows a 0.01 tolerance (money rounding). String `==` ignores case and surrounding spaces.
- If a referenced value is absent the rule is `SKIPPED`, not failed (`required` covers absence).
  Use `exists()` to make presence part of the rule.

## Evaluation harness
```bash
./mvnw package -DskipTests
java -jar target/docextract-0.0.1-SNAPSHOT.jar eval \
     [--corpus=corpus] [--strategy=all|native-text|ocr|vision-llm|auto-routed] [--out=eval-results]
```
Runs every tier, plus `auto-routed` (the API's default routing), over the labelled corpus. It prints
per-document, per-field, summary and tier-comparison tables, and writes:
- `eval-results/fields.csv`: one row per strategy × document × field (outcome, expected, predicted, confidence)
- `eval-results/documents.csv`: one row per strategy × document (accuracy, validation, cost, errors)
- `eval-results/tiers.csv`: one row per strategy (field/document accuracy, valid-but-wrong, cost per document)

A tier that is not configured is reported as `not configured` rather than scored. Documents a tier cannot read
at all (OCR/vision on a DOCX) are listed as skipped and left out of that tier's metrics.

Field outcomes: `CORRECT`, `WRONG` (confident but incorrect), `MISSING` (null). Numbers compare by value,
strings ignoring case and extra whitespace, arrays element-wise in order. Only fields present in `expected`
are scored.

## Corpus format
`corpus/<name>.<ext>` with a sibling `corpus/<name>.labels.json`:
```json
{
  "description": "optional note",
  "schema":   { "type": "object", "properties": { "total": { "type": "number" } } },
  "expected": { "total": 165.20 }
}
```
The loader rejects a labels file with no matching document, or whose `expected` violates its own `schema`.
The four checked-in documents are **synthetic**. To build the real corpus, follow
[docs/CORPUS_CHECKLIST.md](docs/CORPUS_CHECKLIST.md).

Regenerate the synthetic samples (labels are hand-written):
```bash
./mvnw test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test -Dexec.mainClass=com.docextract.corpus.SampleCorpusGenerator
```

## Package layout
| Package | Responsibility |
|---|---|
| `api` | HTTP: controller, per-file result DTO, error mapping |
| `service` | Request handling, `TierRouter` (auto routing), `RoutedStrategy` (routing as an evaluable strategy) |
| `document` | `Document`, Tika-based `DocumentTypeDetector` |
| `strategy` | `ExtractionStrategy`, `CostTier`, the three tiers |
| `strategy.text` | PDFBox/Tika text, text-layer check, page rendering |
| `strategy.ocr` | Tess4J wrapper, scan preprocessing (median filter + Otsu) |
| `strategy.vision` | OpenAI vision client |
| `strategy.mapping` | `FieldMapper`: text → schema fields (keyword/regex baseline) |
| `validation` | `ValidationEngine` (JSON Schema + rule beans + expressions), per-field results |
| `validation.expr` | The rule-expression parser/evaluator |
| `eval` | Scoring, reports, CLI; `eval.corpus` loads labelled documents |

## Adding things
- **New tier:** implement `ExtractionStrategy` as a `@Component`. It is automatically available to the API
  (`tier=<name>`) and the harness. Override `supports(kind)` and `notConfiguredReason()` as needed.
- **New built-in rule:** implement `BusinessRule` as a `@Component`; return no violations when the
  fields it checks are absent. For a one-off rule, use an `x-rules` expression instead.
