# docextract — schema-driven document extraction

POST a document plus a JSON Schema; get back JSON matching the schema with a
confidence score per field.

**Status:** Step 1 (skeleton). `/api/extract` returns a stub response.

## Requirements
- Java 21 (Maven is bundled via `./mvnw`, no install needed)

## Run
```bash
./mvnw spring-boot:run
# or
./mvnw package && java -jar target/docextract-0.0.1-SNAPSHOT.jar
```
Tests: `./mvnw test`

## API

### GET /health
```bash
curl localhost:8080/health
# {"status":"UP"}
```

### POST /api/extract
Multipart form with two parts:
- `file`: the document (PDF, DOCX, image, spreadsheet)
- `schema`: a JSON Schema string describing the wanted fields

```bash
curl -F file=@invoice.pdf \
     -F 'schema={"type":"object","properties":{"invoiceNumber":{"type":"string"},"total":{"type":"number"}}}' \
     localhost:8080/api/extract
```
Response:
```json
{
  "fields":     {"invoiceNumber": null, "total": null},
  "confidence": {"invoiceNumber": 0.0,  "total": 0.0},
  "tier": "STUB",
  "costUnits": 0
}
```

A schema read from a file (note `<`, which sends the file's contents as a text field):
```bash
curl -F file=@invoice.pdf -F 'schema=<schema.json' localhost:8080/api/extract
```

Errors come back as RFC 7807 problem JSON with HTTP 400, e.g. missing file,
missing schema, or a schema that is not a JSON object.

## Package layout
| Package | Responsibility |
|---|---|
| `com.docextract.api` | HTTP: controller, response DTO, error mapping |
| `com.docextract.service` | Orchestrates extraction (stub for now) |

Planned: `strategy` (extraction tiers), `validation` (schema + business
rules), `eval` (corpus harness).
