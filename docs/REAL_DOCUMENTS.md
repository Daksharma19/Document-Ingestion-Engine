# Real test documents

`RealDocumentsTest` runs the engine on real public documents. They are not committed (not ours to
redistribute; `test-docs/` is git-ignored). Download them once:

```bash
mkdir -p test-docs/real && cd test-docs/real
D=https://raw.githubusercontent.com/docling-project/docling/main/tests/data
curl -sfLo attention-1706.03762.pdf https://arxiv.org/pdf/1706.03762
curl -sfLo irs-fw9.pdf https://www.irs.gov/pub/irs-pdf/fw9.pdf
curl -sfLo eurotext.png https://raw.githubusercontent.com/tesseract-ocr/tessdoc/main/images/eurotext.png
for f in 2305.03393v1-pg9 multi_page normal_4pages redp5110_sampled newspaper-00 amt_handbook_sample; do
  curl -sfLo $f.pdf $D/pdf/sources/$f.pdf; done
curl -sfLo invisible_text_layer.pdf $D/pdf/invisible_text_layer.pdf
curl -sfLo 2206.01062.tif $D/tiff/sources/2206.01062.tif
curl -sfLo word_sample.docx $D/docx/sources/word_sample.docx
curl -sfLo word_tables.docx $D/docx/sources/word_tables.docx
curl -sfLo xlsx_01.xlsx $D/xlsx/sources/xlsx_01.xlsx
```

| document | what it exercises |
|---|---|
| attention-1706.03762.pdf | 15-page paper: headings, tables, rotated figure labels |
| irs-fw9.pdf | government form, dense multi-column instructions |
| newspaper-00.pdf | six narrow columns, tight word spacing, soft hyphens |
| redp5110_sampled.pdf | IBM Redbook: one page with a letter-shifted (garbled) text layer, one blank page |
| 2206.01062.tif | 72 DPI scan of a two-column paper, vertical margin stamp |
| eurotext.png | Tesseract's multilingual scan (known ground truth) |
| word_sample.docx, word_tables.docx | Word lists, tables, merged cells |
| xlsx_01.xlsx | multi-sheet spreadsheet |
| 2305.03393v1-pg9, multi_page, normal_4pages, amt_handbook_sample, invisible_text_layer | docling's own PDF fixtures |
