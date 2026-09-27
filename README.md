# docextract — schema-driven document extraction

POST a document (PDF, DOCX, image, spreadsheet, …) plus a JSON Schema; get back JSON matching the
schema, a confidence score per field, and a list of validation violations.

**Status (semester 1 foundation):** one extraction tier (native text via PDFBox/Tika) with a naive
keyword/regex field mapper, a validation engine, a labelled corpus, and an evaluation harness.
Tier routing and the OCR / layout / vision tiers come later.

## Requirements
- Java 21 (Maven is bundled via `./mvnw`, no install needed)

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
Multipart form with two parts:
- `file`: the document
- `schema`: a JSON Schema string describing the wanted fields

```bash
curl -F file=@corpus/invoice-001.pdf \
     -F 'schema={"type":"object","required":["invoiceNumber","total"],"properties":{"invoiceNumber":{"type":"string"},"invoiceDate":{"type":"string","format":"date"},"total":{"type":"number"},"vendor":{"type":"string"}}}' \
     localhost:8080/api/extract
```
Response:
```json
{
  "fields":     {"invoiceNumber": "INV-2024-001", "invoiceDate": "2024-03-12", "total": 165.20, "vendor": "ACME Supplies Ltd"},
  "confidence": {"invoiceNumber": 0.8, "invoiceDate": 0.8, "total": 0.8, "vendor": 0.8},
  "tier": "NATIVE_TEXT",
  "costUnits": 1,
  "violations": []
}
```

Schema from a file (`<` sends the file's contents as a text field):
```bash
curl -F file=@invoice.pdf -F 'schema=<schema.json' localhost:8080/api/extract
```

Schema hints the baseline understands: `title` and `x-labels` (extra label text to search for), e.g.
`"grandTotal": {"type": "number", "x-labels": ["Amount Payable"]}`.

Errors are RFC 7807 problem JSON: 400 for a bad request (missing file/schema, invalid JSON),
422 when the document cannot be parsed.

## Evaluation harness
```bash
./mvnw package -DskipTests
java -jar target/docextract-0.0.1-SNAPSHOT.jar eval \
     [--corpus=corpus] [--strategy=all|native-pdf-text] [--out=eval-results]
```
Prints per-document, per-field and summary tables and writes:
- `eval-results/fields.csv` — one row per strategy × document × field (outcome, expected, predicted, confidence)
- `eval-results/documents.csv` — one row per strategy × document (accuracy, validation, cost, errors)

Field outcomes: `CORRECT`, `WRONG` (confident but incorrect), `MISSING` (null). Numbers compare by
value, strings ignoring case and extra whitespace, arrays element-wise in order. Only fields
present in `expected` are scored.

Metrics: field accuracy (micro, pooled), document accuracy (macro, mean per document),
exact-match rate, valid-but-wrong documents (passed validation yet incorrect), total and mean cost units.

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

Sample documents are generated reproducibly (labels are hand-written):
```bash
./mvnw test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test -Dexec.mainClass=com.docextract.corpus.SampleCorpusGenerator
```

## Package layout
| Package | Responsibility |
|---|---|
| `api` | HTTP: controller, response DTO, error mapping |
| `service` | Orchestrates extract → validate for a request |
| `document` | `Document` (filename, content type, bytes) |
| `strategy` | `ExtractionStrategy` interface, `CostTier`, `NativePdfTextStrategy` |
| `strategy.text` | Text acquisition: PDFBox for PDFs, Tika for everything else |
| `strategy.mapping` | `FieldMapper`: text → schema fields (keyword/regex baseline) |
| `validation` | `ValidationEngine` (JSON Schema via networknt) + pluggable `BusinessRule`s |
| `eval` | Scoring, reports, CLI; `eval.corpus` loads labelled documents |

## Adding things
- **New tier:** implement `ExtractionStrategy` as a `@Component`; it is automatically available to
  the API and the harness (`--strategy=<name>`).
- **New business rule:** implement `BusinessRule` as a `@Component`; return no violations when the
  fields it checks are absent.
