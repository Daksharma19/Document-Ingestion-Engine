# docextract Logbook, September to November 2026

Same format as the InfraScope logbook: Week | Date Range | Work Completed (one line).
Each week below also has a short write-up (about 100 to 120 words, under half a page) to copy into the notebook.

| Week | Date Range | Work Completed |
|---|---|---|
| Week 1 | Sep 2 – Sep 8, 2026 | Studied the document extraction problem, compared OCR, rule-based and LLM approaches, and fixed project requirements. |
| Week 2 | Sep 9 – Sep 15, 2026 | Designed the tiered pipeline (native text, OCR, vision LLM) with JSON Schema as the output contract. |
| Week 3 | Sep 16 – Sep 22, 2026 | Set up the Spring Boot project, built /api/extract and /health, and added PDFBox/Tika native text extraction. |
| Week 4 | Sep 23 – Sep 29, 2026 | Built the baseline field mapper, validation engine, labelled corpus format and evaluation harness CLI. |
| Week 5 | Sep 30 – Oct 6, 2026 | Added the multi-file API, Tika type detection and routing, and declarative business rules. |
| Week 6 | Oct 7 – Oct 13, 2026 | Implemented the OCR tier and vision-LLM tier and compared all tiers on accuracy and cost. |
| Week 7 | Oct 14 – Oct 20, 2026 | Built generic invoice mapping: field vocabulary, date and currency handling, tax summary and line items. |
| Week 8 | Oct 21 – Oct 27, 2026 | Added layout-aware extraction and schema-driven fallbacks, then tested on real invoices and other documents. |
| Week 9 | Oct 28 – Nov 3, 2026 | Built the React frontend for uploading documents, entering a schema and viewing extraction results. |
| Week 10 | Nov 4 – Nov 10, 2026 | Added the Python OCR service (RapidOCR, ONNX Runtime), GPU support and OCR text clean-up. |
| Week 11 | Nov 11 – Nov 17, 2026 | Developed the v2 parse engine with per-page routing, OCR tiers and quality-based escalation. |
| Week 12 | Nov 18 – Nov 24, 2026 | Redesigned the frontend with Parse mode, added .zip upload, fixed PDF layout and built the benchmark dashboard. |

---

## Week 1: Sep 2 – Sep 8, 2026
This week I studied the problem of extracting structured data from invoices, forms and scanned documents. I read about the common approaches: plain text extraction, OCR with Tesseract, rule and regex mapping, and vision LLMs. Each has a different accuracy and cost. Plain text is cheap but fails on scans. OCR works on scans but is slower and makes mistakes. LLMs are accurate but expensive. From this I decided the system should take any document plus a JSON Schema and return JSON that matches the schema. It should also give a confidence score per field and a pass/fail validation result. I wrote down the requirements and the evaluation goals.

## Week 2: Sep 9 – Sep 15, 2026
I designed the overall architecture. The main idea is a tiered pipeline: native text first, then OCR, then a vision LLM, each with a relative cost weight, so the cheapest tier that works is chosen. I defined an ExtractionStrategy interface that every tier implements, plus a CostTier value. JSON Schema was chosen as the contract between the user and the system, with custom `x-labels` hints for field names. I picked Java 21 with Spring Boot 3 for the API and Maven for the build. I also drew the data flow: upload, type detection, extraction, field mapping, validation, response.

## Week 3: Sep 16 – Sep 22, 2026
I created the Spring Boot project and set up the Maven wrapper. I built `POST /api/extract`, first as a stub that returned a fixed response, and `GET /health`. Then I implemented the first extraction strategy, native text. It uses PDFBox for PDFs and Apache Tika for other formats such as DOCX and plain text. I tested it on a few sample documents and checked that the text came out in the right order. I also wrote the first version of the README with the requirements and run steps, so the project can be started with one command.

## Week 4: Sep 23 – Sep 29, 2026
I built the baseline field mapper. It finds labels such as "Invoice No" or "Total" and takes the value next to them, and it also handles dates and currency. After that I wrote the validation engine. It checks the extracted JSON against the schema using the networknt library and allows extra business rules to be plugged in. I defined a labelled corpus format (document plus expected JSON), a loader, and three sample documents. Finally I wrote an evaluation harness CLI that reports per-field accuracy, per-document accuracy and cost, and saves CSV files. On Sep 27 I moved the Java target back to 21 after build problems with 25.

## Week 5: Sep 30 – Oct 6, 2026
I extended the API to accept several files in one request, with one schema, and return one result per file. Each file gets its own status: OK, TIER_NOT_CONFIGURED, UNSUPPORTED or ERROR, so a bad file does not break the whole request. I added file type detection from the bytes using Tika instead of trusting the extension. Routing rules were added: PDFs with a text layer go to native text, scans and images go to OCR, and Office and text files go to Tika. I also made validation stricter: format checks are on, null is treated as missing, and rules can be written as declarative expressions.

## Week 6: Oct 7 – Oct 13, 2026
This week I added the two remaining tiers. The OCR tier uses Tess4J (Tesseract). Pages are rendered at 300 DPI and cleaned with a median filter and Otsu thresholding before recognition. The vision-LLM tier sends page images and the schema to the model in JSON mode and projects the reply onto the schema. It stays disabled, returning TIER_NOT_CONFIGURED, until an API key is set, so it never returns made-up output. I updated the harness to compare all tiers and an auto-routed mode. On the 4 synthetic documents, OCR reached about 94% field accuracy and native text about 45%. The test count reached 89.

## Week 7: Oct 14 – Oct 20, 2026
I generalised the mapper so it is not tied to one invoice layout. It now recognises a field from its name (`grandTotal`, `amount_due`, `billNo`) and uses ordered label synonyms, for example Amount Payable before Grand Total before Total. Dates are parsed in many formats (`28-09-2026`, `14.02.25`, `12-Mar-2024`), Indian lakh number grouping is handled, and percentage rates are not mistaken for amounts. Currency is detected from symbols. Tax summary rows such as CGST+SGST or VAT bands are summed when no tax total is printed. I also wrote a generic line-item table reader and tested on one real Indian POS invoice. Tests reached 95.

## Week 8: Oct 21 – Oct 27, 2026
I made extraction layout-aware. Wide gaps in PDF text are treated as column breaks. A normaliser turns a header row over a value row ("DUE DATE" above "11/12/2026") and block labels ("From:" above a name) into "Label: value" form. The date order (day-first or month-first) is inferred per document. Values placed directly after a label are preferred. I added schema-driven fallbacks using `pattern`, `enum` and `format: email/uri`, with no invoice-specific guessing. On 4 real invoices native text reached 100% field accuracy (I noted that this is an upper bound because I tuned on them). A non-invoice schema on 3 PDFs gave 30 of 30 correct fields.

## Week 9: Oct 28 – Nov 3, 2026
I built the first version of the React + TypeScript frontend with Vite. The user can upload one or many files, paste or choose a JSON Schema, pick a tier and run extraction. The result view shows the extracted JSON, confidence per field, pass/fail per field and the list of rule violations. I added loading, error and empty states, and connected it to `/api/extract` and `/api/schemas`. I checked the full flow in a browser against the running API. I also fixed a few cases where fields came back null because of how the response was parsed in the UI.

## Week 10: Nov 4 – Nov 10, 2026
The Tesseract results on logos, the ₹ symbol and small text were weak (65.6% on the real invoices), so I built a separate Python OCR service using RapidOCR (PP-OCR models on ONNX Runtime). Java keeps routing, mapping and validation and sends documents to the service on port 8001. With `engine=auto` it falls back to Tesseract if the service is down. I added optional GPU support (CUDA and DirectML) with a warm-up check and a fall-back to CPU, then measured about 5.1 s per page on CPU and 3.6 to 4.5 s with detection on the GPU. I also added noise-gated OCR text clean-up.

## Week 11: Nov 11 – Nov 17, 2026
I built the v2 parse engine. Instead of choosing one tier for a whole document, it now decides per page. A page with a good text layer uses native text, and a scanned page goes to OCR. After each step a quality check scores the result, and if the score is too low the page escalates to the next, more expensive tier. This saves cost on mixed documents, such as a PDF with a digital cover page and scanned attachments. I added the `/api/parse` endpoint for this mode and tests for the routing and escalation rules. The work was committed on Nov 17 on the `v2-parse-engine` branch.

## Week 12: Nov 18 – Nov 24, 2026
I redesigned the frontend with a new layout and a Parse mode for the v2 engine, and added a script (`run-all`) that starts the API, OCR service and UI together. The API now accepts a .zip file of many documents on both `/api/extract` and `/api/parse`. I fixed native PDF layout problems: reading order, line building and page parsing. Finally I wrote a parser benchmark and an analysis dashboard that compare tiers on accuracy and cost, with per-service start scripts. Remaining work is the final report, the held-out evaluation and final testing.
