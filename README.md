# docextract — schema-driven document extraction

POST one or more documents (PDF, DOCX, image, spreadsheet, …) plus a JSON Schema. For each document you get
back JSON matching the schema, a confidence score per field, a validation pass/fail per field, and the list
of violations.

**Status (phase 2):** three working tiers (native text, OCR, plus the vision LLM, which works once an API key
is set), automatic routing by file type, a validation engine with declarative business rules, and an evaluation
harness that compares tiers on accuracy and cost. See [PROGRESS.md](PROGRESS.md).

## Requirements
- Java 21 (Maven is bundled via `./mvnw`)
- OCR (recommended): the Python OCR service in [`ocr-service/`](ocr-service) (RapidOCR with PP-OCR models on ONNX
  Runtime; Python 3.10+). Java keeps routing, mapping and validation and sends documents there. Start it with
  `ocr-service/run.ps1` (Windows) or `ocr-service/run.sh`: the first run creates `.venv` and installs
  `requirements.txt`; it then listens on `:8001` (`docextract.ocr.service-url`, env `OCR_SERVICE_URL`).
  With `docextract.ocr.engine=auto` (default) the OCR tier uses the service when it is up and falls back to
  in-process Tesseract when it is not; `python` / `tesseract` force one engine.
  GPU (optional): `OCR_DEVICE=auto|cpu|dml|cuda` (default `auto`: use the GPU provider the installed onnxruntime
  package offers, else CPU), `OCR_GPU_ID` (adapter index; DirectML 0 = the Windows default GPU) and
  `OCR_GPU_STAGES=det|all` (default `det`: only text detection runs on the GPU, recognition stays on the CPU). Swap
  `onnxruntime` for `onnxruntime-directml` (any DirectX 12 GPU) or `onnxruntime-gpu` (NVIDIA, plus its CUDA/cuDNN
  runtime) in the venv. Every candidate device runs a warm-up page at startup and the service drops to CPU if it
  fails, or if the GPU fails later mid-request; `GET /health` reports the providers in use. Output text is
  identical on CPU and GPU. Measured on a laptop (i5-10210U, on AC, 7 pages, steady state; detection on GPU,
  recognition on CPU): CPU ~5.1 s/page; CUDA on the MX250 3.6-4.5 s/page (detection 0.7-1.6 s vs 2.2 s); DirectML on
  the Intel UHD 620 4.2-4.8 s/page (the first page of each new size is slower while DirectML compiles it).
  Recognition on either GPU was slower than on the CPU, hence the `det` default. Pascal cards such as the MX250 need a
  CUDA 12 build: `pip install "onnxruntime-gpu[cuda,cudnn]==1.26.0" "nvidia-cudnn-cu12==9.5.1.17"` (1.27+ is CUDA
  13, and newer cuDNN 9 releases fail on Pascal; the service then falls back to CPU).
- OCR fallback: Tesseract language data `eng.traineddata` in `.tools/tessdata/` (or set `TESSDATA_PREFIX`). Tess4J
  bundles the native Tesseract library for Windows x64; on Linux/macOS install `tesseract` (5.x) from your package
  manager. Without the data file the OCR tier reports `TIER_NOT_CONFIGURED`.
  ```bash
  mkdir -p .tools/tessdata && curl -L -o .tools/tessdata/eng.traineddata https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata
  ```
- Vision tier (optional): `OPENAI_API_KEY` in the environment, in a git-ignored `.env` file at the repo root
  (`OPENAI_API_KEY=sk-...`), or a git-ignored `.tools/secrets.properties`
  containing `docextract.vision.api-key=...`. Without it the tier reports `TIER_NOT_CONFIGURED` and never
  returns made-up output.
- OCR tuning (application.properties): `docextract.ocr.page-seg-mode` (6), `docextract.ocr.dpi` (300),
  `docextract.ocr.preprocess` (auto = clean only speckled scans | always | never), `docextract.ocr.layout-clean`
  (true = invert dark header bars and erase table rules before OCR).

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
| `schema` | yes | JSON Schema text, **or** a built-in schema id (`invoice`, `bank-statement`, `payslip`, …), **or** `auto` (classify each document and use the matching built-in schema) |
| `rules` | no | JSON array of business-rule expressions, e.g. `["subtotal + tax == total"]`. Omitted with a built-in schema: that schema's default rules apply |
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

### GET /api/schemas
The built-in schemas (`src/main/resources/schemas/*.json`): id, label, category, description, classification
keywords, default rules and the JSON Schema. `GET /api/schemas/{id}` returns one.

| id | for |
|---|---|
| `invoice`, `invoice-detailed`, `gst-invoice` | invoices (basic; with seller/buyer blocks and line items; Indian GST with GSTINs and CGST/SGST/IGST) |
| `receipt`, `purchase-order`, `quotation`, `credit-note` | other commercial documents |
| `bank-statement` | account details, balances and the transaction table (date / description / debit / credit / balance) |
| `payslip`, `resume` | HR documents |
| `utility-bill`, `shipping-document`, `id-document`, `insurance-policy` | bills, logistics, identity, insurance |
| `generic` | key facts from anything (organisation, date, reference, contact details, total); also the `auto` fallback |

With `schema=auto` each result also carries `schemaId` and `documentType`. The classifier scores each schema's
keywords (3 points in the first lines, where documents name themselves, 1 elsewhere).

### Routing (`tier=auto`)
The type is detected from the bytes with Tika, not from the client's filename or content type.

| detected | route |
|---|---|
| PDF with a text layer (≥ 20 letters/digits per page) | native text (PDFBox) |
| PDF without one (scan) | OCR |
| image (PNG/JPEG/TIFF/…) | OCR |
| DOCX, XLSX, HTML, text, … | native text (Tika) |

**Escalation:** when the cheap tier's result is weak (a required field is empty, validation failed, or with no
required fields most fields came back empty) the next tier that can read the document is tried (native text → OCR;
→ vision only with `docextract.routing.escalate-to-vision=true`), and the better result is kept. Both tiers' cost is
reported and `routing` says what happened, e.g. `PDF has a text layer; escalated to ocr (native-text left 1 required
field empty)`. Disable with `docextract.routing.escalate=false`.

### Tiers
| strategy | tier | cost units | how |
|---|---|---|---|
| `native-text` | NATIVE_TEXT | 1 | PDFBox text layer / Tika, then the keyword-regex field mapper |
| `ocr` | OCR | 20 | Python service: PDF rendered at 300 DPI (pypdfium2), speckle-gated denoise, PP-OCR detection on a downscaled copy + recognition on full-resolution crops, boxes regrouped into lines with column gaps; fallback: Tess4J. Then the same mapper. `engine` in the result says which ran |
| `vision-llm` | VISION_LLM | 100 | pages rendered at 150 DPI (max 3) + the schema sent to OpenAI Chat Completions in JSON mode; the reply is projected onto the schema's properties |

Cost units are relative (from the phase 1 tier design), not money. Vision settings live in
`application.properties` (`docextract.vision.model`, default `gpt-4o-mini`).

### Schema hints the mapper understands
`title` and `x-labels` (extra label text to search for), e.g.
`"grandTotal": {"type": "number", "x-labels": ["Amount Payable"]}`.
- Types: `string`, `number`, `integer`, `boolean` (labelled yes/no/✓), `string` + `format: date`, arrays of strings
  (bullets under a heading), arrays of objects (tables, including bank-statement tables), and nested `object`s:
  `"vendor": {"type":"object","properties":{"name":…,"address":…}}` is read as `vendorName`, `vendorAddress`.
- Field names imply value shapes: `email`, `phone`/`mobile`, `website`, `linkedin`, `github`, `iban`, `ifsc`,
  `gstin`, `pan`, `swift`, `zip`/`pincode`, `…time`. A labelled value must have the shape; distinctive shapes
  (e-mail, IBAN, GSTIN…) are also found without a label, but never for the receiving party (`buyerGstin`,
  `customerEmail`), whose values would otherwise be the issuer's.
- `…address` fields continue onto the following address-like lines.

## Speed
Speed is a design goal: the cheapest tier that can read a document is used, nothing is read twice, and OCR does
as little work as possible. Every result reports `elapsedMs` (shown as "Time" in the UI).

| what | how |
|---|---|
| Text-layer PDFs, Office files | never OCR'd: PDFBox/Tika text, parsed **once** per document (routing and extraction share it). ~0.1–0.3 s |
| Repeat uploads | read results are cached by content hash (`docextract.cache.max-entries`, default 256): the same file with a new schema, another tier or a re-run is answered in ~20–150 ms |
| Batches | files of one request run in parallel (`docextract.parallelism`, default = CPU count) |
| First request | a warm-up extraction runs at startup, so the first batch does not pay for class loading and PDFBox font caching |
| OCR detection | on the GPU when one is available (CUDA/DirectML, `OCR_DEVICE`), on a 1600 px copy of the page |
| OCR recognition | Latin-script copy of the PP-OCRv6 recogniser (output layer cut from 18,710 to 419 characters; identical text on these documents, ~17–34% faster; `OCR_REC_MODEL=full` restores CJK); long lines cut at word gaps (the recogniser's cost grows faster than line length; `OCR_SPLIT_RATIO`); several CPU recognisers side by side (`OCR_REC_WORKERS`) |
| OCR concurrency | pages and documents overlap (one detecting while another is recognised), at most `OCR_MAX_CONCURRENT` (2) documents in flight so a burst does not oversubscribe the CPU |

Measured on a 4-core i5-10210U laptop with an MX250 (noisy: the CPU throttles), 9 sample files:

| | before | now |
|---|---|---|
| 9-file batch, `tier=auto`, first time | ~15 s (first request after a restart) | 3.9 s |
| the same batch again | full re-read | 0.22 s |
| dense résumé page, OCR | 11.5 s | 6.1–6.9 s |
| 2-page POS receipt, OCR | 12.9 s | 7.2–8.5 s |
| text-layer PDF, native | 0.2–0.6 s | 0.1–0.3 s |

OCR field accuracy was re-checked after every change: 96.2% agreement with the native text layer on the real
documents and 10/10 on the labelled scans, the same as before the speed work. Tried and rejected: PP-OCRv6 tiny
(faster but lost fields), English-only PP-OCRv4/v5 models (slower here), int8 quantisation (this CPU has no
VNNI: slower and less accurate), lower render DPI (recognition cost does not depend on it).

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
| `strategy.ocr` | Python OCR service client, Tess4J fallback, scan preprocessing (median filter + Otsu) |
| `strategy.vision` | OpenAI vision client |
| `strategy.mapping` | `FieldMapper`: text → schema fields (keyword/regex baseline) |
| `schema` | Built-in schema catalog and the document classifier behind `schema=auto` |
| `validation` | `ValidationEngine` (JSON Schema + rule beans + expressions), per-field results |
| `validation.expr` | The rule-expression parser/evaluator |
| `eval` | Scoring, reports, CLI; `eval.corpus` loads labelled documents |

`ocr-service/` (Python): `app.py` (FastAPI), `engine.py` (rendering, clean-up, RapidOCR), `layout.py` (boxes →
lines with column gaps). Tests: `ocr-service/.venv/Scripts/python -m unittest discover -s ocr-service`.

## Adding things
- **New built-in schema:** drop a JSON file in `src/main/resources/schemas/` (`id`, `label`, `category`,
  `description`, `keywords`, `rules`, `schema`, `order`). It appears in `/api/schemas`, the frontend and `auto`.
- **New tier:** implement `ExtractionStrategy` as a `@Component`. It is automatically available to the API
  (`tier=<name>`) and the harness. Override `supports(kind)` and `notConfiguredReason()` as needed.
- **New built-in rule:** implement `BusinessRule` as a `@Component`; return no violations when the
  fields it checks are absent. For a one-off rule, use an `x-rules` expression instead.
