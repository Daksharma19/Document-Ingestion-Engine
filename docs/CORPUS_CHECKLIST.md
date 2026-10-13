# Corpus collection & labelling checklist

The four files in `corpus/` are synthetic and only prove the pipeline runs. For results you can report,
collect **real** documents and label them by hand. Target: **60–100 documents**. That is enough for
per-tier accuracy to mean something (±10% at n≈80), and small enough to label in a few evenings.

## 1. What to collect

Aim for a spread across the four routes the system makes. Rough quotas:

| # | Bucket | Target | Why | Where to get them |
|---|---|---|---|---|
| ☐ | **Digital PDFs with a text layer** (invoices, utility bills, bank statements exported as PDF) | 25 | Native-text tier; cheapest route | Your own e-bills, online-shop invoices, college fee receipts |
| ☐ | **Scanned / photographed PDFs** (no text layer) | 20 | OCR tier; the main reason for routing | Phone-scan paper bills (CamScanner/Adobe Scan export, *without* their OCR) |
| ☐ | **Phone photos** (JPG/PNG) of receipts | 15 | OCR on hard input: skew, shadows, curved paper | Shop/restaurant till receipts |
| ☐ | **DOCX / XLSX** documents | 10 | Tika route; OCR and vision cannot read these | Quotations, purchase orders, templates people fill in Word |
| ☐ | **Hard cases** | 10+ | Where the cheap tiers should fail and escalation matters | Two-column layouts, tables with line items, handwritten amounts, non-English labels, faded thermal paper, multi-page invoices |

Also cover:
- ☐ At least **3 different document types** (e.g. invoice, receipt, bank statement), so the schema is not overfitted to one layout.
- ☐ At least **10 different issuers/vendors**. Twenty bills from one company measure one template, not the method.
- ☐ Some **USD-style dates** (`03/12/2024` = March 12) and some **day-first** ones. The mapper currently assumes day-first; you want this measured, not hidden.
- ☐ A few **multi-page** documents (the OCR tier reads up to 10 pages, the vision tier up to 3).

## 2. Privacy before anything else

- ☐ Only collect documents you own or have written permission to use.
- ☐ Redact personal data that is **not** a field you extract (account numbers, phone numbers, home addresses) by
  blacking it out **in the image itself** before scanning or exporting. A black rectangle drawn in a PDF viewer
  often leaves the text underneath.
- ☐ Do **not** commit real documents to a public GitHub repository. Keep `corpus-real/` out of git (add it to
  `.gitignore`) or use a private repository.
- ☐ Remember the vision tier **uploads page images to OpenAI**. Only run it on documents you are allowed to share with a third party.

## 3. File naming

```
corpus-real/
  inv-001.pdf            inv-001.labels.json
  rcpt-014.jpg           rcpt-014.labels.json
  po-003.docx            po-003.labels.json
```
- ☐ `<type>-<number>.<ext>`, with exactly one document per labels file (the loader rejects anything else).
- ☐ Keep a private spreadsheet mapping each ID to its source (who, when, original filename). Do not put
  this in the labels.

## 4. Pick the schemas first, then label

Decide 2–3 schemas **before** labelling and keep them fixed. Changing a schema halfway means relabelling.
Suggested invoice schema (rename fields if you like, but use the same names everywhere):

```json
{
  "type": "object",
  "required": ["invoiceNumber", "invoiceDate", "total"],
  "x-rules": ["subtotal + tax == total", "sum(lineItems[].amount) == subtotal"],
  "properties": {
    "vendor":        { "type": "string" },
    "invoiceNumber": { "type": "string" },
    "invoiceDate":   { "type": "string", "format": "date" },
    "dueDate":       { "type": "string", "format": "date" },
    "currency":      { "type": "string" },
    "subtotal":      { "type": "number" },
    "tax":           { "type": "number" },
    "total":         { "type": "number" },
    "lineItems": { "type": "array", "items": { "type": "object",
        "properties": { "description": { "type": "string" }, "amount": { "type": "number" } } } }
  }
}
```

## 5. Labelling rules (write these down and follow them every time)

Each `<name>.labels.json` file:
```json
{
  "description": "Phone photo of a supermarket receipt, slight skew, thermal paper",
  "schema":   { ... },
  "expected": { ... }
}
```

- ☐ **Label what is printed, not what is correct.** If the document says Total 100.00 but the items add up to
  99.00, label `100.00`. (Validation is supposed to catch exactly this kind of document.)
- ☐ **Dates:** always ISO `YYYY-MM-DD`, after deciding day/month order from the document's country.
- ☐ **Numbers:** plain JSON numbers, no currency symbol, no thousands separator (`1234.50`, not `"₹1,234.50"`).
  Put the currency in its own field if you need it.
- ☐ **Strings:** copy exactly as printed, keeping the spelling and spacing. Scoring ignores case and extra spaces.
- ☐ **Absent on the document → leave the key out of `expected`.** Do not write `null` unless you want to test
  that the system returns nothing (an explicit `null` is scored: predicting a value there counts as WRONG).
  If a field in `required` is genuinely missing from a document, remove it from `required` in *that* file's
  schema. The loader rejects labels that violate their own schema.
- ☐ **Line items:** in printed order, with every row included. If a row has no amount, leave out that row's `amount` key.
- ☐ **Unreadable to you as a human?** Leave the field out and note it in `description`. Do not guess.
- ☐ Write a one-line `description` with the conditions (scan quality, layout, language). You will group results by it later.

## 6. Quality control

- ☐ Run the loader after every batch. It rejects labels that violate their own schema:
  `java -jar target/docextract-0.0.1-SNAPSHOT.jar eval --corpus=corpus-real --strategy=native-text`
- ☐ **Double-label 10%** (about 8 documents): have a friend label them independently from the same rules, then
  compare. Every disagreement is either a rule to clarify or a label to fix. Report the agreement rate.
- ☐ Freeze a **held-out test split** (about 20%) *before* you tune any heuristics, and look at it only for the
  final numbers. Tuning the mapper on the same documents you report on inflates the results. The current
  synthetic corpus already suffers from this.

## 7. When it is done

- ☐ Run `eval --corpus=corpus-real --strategy=all` and keep `eval-results/tiers.csv` for the report.
- ☐ Record in the report: document count per bucket, the double-label agreement rate, and the date of the run.
