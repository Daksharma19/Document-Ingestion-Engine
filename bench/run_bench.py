"""Run every available parser over a set of documents and score the output.

    python bench/run_bench.py corpus test-docs            # all parsers
    python bench/run_bench.py corpus -p pypdf tesseract   # a subset
    python bench/run_bench.py mydoc.pdf

Scoring (all optional, used when the ground truth exists next to the document):
  * <name>.labels.json  -> field recall: share of `expected` values found in the output
  * <name>.gt.txt       -> CER / WER against the reference transcript
Always reported: time, chars, words, error.

Writes bench/results/<timestamp>/{results.csv, outputs/<parser>/<doc>.txt, report.html}.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import traceback
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from metrics import category_of, score  # noqa: E402
from parsers import Unsupported, available  # noqa: E402

DOC_EXT = {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp", ".docx", ".pptx", ".xlsx", ".html"}


def collect(paths: list[str]) -> list[Path]:
    docs = []
    for p in map(Path, paths):
        if p.is_dir():
            docs += sorted(f for f in p.rglob("*") if f.suffix.lower() in DOC_EXT)
        elif p.exists():
            docs.append(p)
    return docs


def page_count(doc: Path) -> int:
    if doc.suffix.lower() != ".pdf":
        return 1
    try:
        import pypdfium2 as pdfium
        return len(pdfium.PdfDocument(str(doc)))
    except Exception:
        return 1


def sidecar(doc: Path, suffix: str) -> Path | None:
    p = doc.with_name(doc.stem + suffix)
    return p if p.exists() else None


FIELDS = ["parser", "document", "category", "ext", "status", "seconds", "pages", "chars", "words",
          "field_recall", "fields_total", "missing", "table_rows", "reading_order", "headings",
          "word_f1", "cer", "wer", "dup_ratio", "error"]


def run(docs: list[Path], parsers: dict, out: Path, warmup: bool = True, on_row=None) -> list[dict]:
    rows = []
    for name, fn in parsers.items():
        if warmup and docs:  # model loading shouldn't count against the first document
            try:
                fn(docs[0])
            except Exception:
                pass
        for doc in docs:
            row = dict.fromkeys(FIELDS, "")
            row.update(parser=name, document=doc.name, ext=doc.suffix.lower(),
                       category=category_of(sidecar(doc, ".labels.json")))
            t0 = time.perf_counter()
            try:
                text = fn(doc)
                row["status"] = "ok"
            except Unsupported:
                row["status"] = "unsupported"
                rows.append(row)
                if on_row:
                    on_row(row)
                continue
            except Exception as e:
                text = ""
                row["status"] = "error"
                row["error"] = f"{type(e).__name__}: {e}"[:300]
                traceback.print_exc(limit=1)
            row["seconds"] = round(time.perf_counter() - t0, 3)
            row["chars"], row["words"] = len(text), len(text.split())
            row["pages"] = page_count(doc)
            (out / "outputs" / name).mkdir(parents=True, exist_ok=True)
            (out / "outputs" / name / f"{doc.name}.txt").write_text(text)
            if row["status"] == "ok":
                row.update(score(text, sidecar(doc, ".labels.json"), sidecar(doc, ".gt.txt")))
            print(f"{name:16} {doc.name:45} {row['status']:6} {row['seconds']:>7}s  recall={row['field_recall']}")
            rows.append(row)
            if on_row:
                on_row(row)
    with (out / "results.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return rows


def rescore(out: Path, roots: list[str]) -> int:
    """Recompute field recall / CER from saved outputs (after adding or editing ground truth)."""
    docs = {d.name: d for d in collect(roots)}
    rows = list(csv.DictReader((out / "results.csv").open()))
    for row in rows:
        doc, f = docs.get(row["document"]), out / "outputs" / row["parser"] / f"{row['document']}.txt"
        if doc:
            row["category"] = category_of(sidecar(doc, ".labels.json"))
        if row["status"] != "ok" or not doc or not f.exists():
            continue
        row.update(score(f.read_text(errors="replace"), sidecar(doc, ".labels.json"), sidecar(doc, ".gt.txt")))
    with (out / "results.csv").open("w", newline="") as fh:
        w = csv.DictWriter(fh, FIELDS, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("-p", "--parsers", nargs="*")
    ap.add_argument("-o", "--out", default=None)
    ap.add_argument("--rescore", metavar="RESULT_DIR", help="re-score saved outputs instead of running parsers")
    a = ap.parse_args()
    if a.rescore:
        from report import build_report
        print("rescored", rescore(Path(a.rescore), a.paths), "rows; report:", build_report(Path(a.rescore)))
        return
    parsers = available()
    if a.parsers:
        parsers = {k: v for k, v in parsers.items() if k in a.parsers}
    print("parsers:", ", ".join(parsers) or "(none installed)")
    docs = collect(a.paths)
    out = Path(a.out or Path(__file__).parent / "results" / datetime.now().strftime("%Y%m%d-%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    run(docs, parsers, out)
    from report import build_report
    print("report:", build_report(out))


if __name__ == "__main__":
    main()
