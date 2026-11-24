"""Scorecard: turn results.csv into one 0-1 score per comparison dimension, plus a static report.html."""
from __future__ import annotations

import math
from pathlib import Path

import pandas as pd

OURS = {"docextract": "docextract ★ (ours: full engine)", "rapidocr-engine": "ocr-service ★ (ours: OCR stage only)"}

# dimension -> (results column, which documents count, what it means)
DIMENSIONS = {
    "Content (digital)": ("field_recall", lambda d: d.category.ne("scanned") & d.category.ne(""),
                          "labelled values found, digital PDFs + Office files"),
    "Content (scanned)": ("field_recall", lambda d: d.category.eq("scanned"),
                          "labelled values found, scans and photos"),
    "Word accuracy": ("word_f1", None, "word-level F1 against a full transcript"),
    "Table rows": ("table_rows", None, "table rows kept together on one line"),
    "Reading order": ("reading_order", None, "snippets emitted in true reading order (columns, sections)"),
    "Headings": ("headings", None, "headings emitted as Markdown structure"),
}
NUMERIC = ["seconds", "pages", "chars", "words", "field_recall", "table_rows", "reading_order", "headings",
           "word_f1", "cer", "wer", "dup_ratio"]
SPEED_BEST, SPEED_WORST = 0.01, 30.0  # seconds/page mapped to 1 and 0 (log scale)


def load(out: Path) -> pd.DataFrame:
    df = pd.read_csv(out / "results.csv", dtype={"category": str}, keep_default_na=True)
    for c in NUMERIC:
        df[c] = pd.to_numeric(df.get(c), errors="coerce")
    df["category"] = df.get("category", pd.Series("", index=df.index)).fillna("")
    df["sec_per_page"] = df.seconds / df.pages.fillna(1).clip(lower=1)
    return df


def label(p: str) -> str:
    return OURS.get(p, p)


def _dim_score(df: pd.DataFrame, col: str, rows) -> pd.Series:
    """Mean over every document that has this check; a parser that fails or can't open the file scores 0 there."""
    has_check = df.groupby("document")[col].transform(lambda s: s.notna().any())
    sub = df[has_check & (rows(df) if rows else True)]
    if sub.empty:
        return pd.Series(dtype=float)
    return sub.assign(v=sub[col].where(sub.status == "ok", 0).fillna(0)).groupby("parser").v.mean()


def speed_score(sec: float) -> float:
    if not sec or math.isnan(sec):
        return float("nan")
    return max(0.0, min(1.0, 1 - math.log10(sec / SPEED_BEST) / math.log10(SPEED_WORST / SPEED_BEST)))


def scorecard(df: pd.DataFrame) -> pd.DataFrame:
    s = pd.DataFrame({name: _dim_score(df, col, rows) for name, (col, rows, _) in DIMENSIONS.items()})
    s["Coverage"] = df.groupby("parser").status.apply(lambda x: (x == "ok").mean())
    med = df[df.status == "ok"].groupby("parser").sec_per_page.median()
    s["Speed"] = med.map(speed_score)
    quality = list(DIMENSIONS)
    s["Overall"] = s[quality + ["Coverage"]].mean(axis=1)  # speed reported, not folded into quality
    return s.sort_values("Overall", ascending=False).round(3)


def facts(df: pd.DataFrame) -> pd.DataFrame:
    ok = df[df.status == "ok"]
    return pd.DataFrame({
        "files parsed": df.groupby("parser").status.apply(lambda x: f"{(x == 'ok').sum()}/{len(x)}"),
        "unsupported": df.groupby("parser").status.apply(lambda x: (x == "unsupported").sum()),
        "errors": df.groupby("parser").status.apply(lambda x: (x == "error").sum()),
        "median s/page": ok.groupby("parser").sec_per_page.median().round(3),
        "total s": ok.groupby("parser").seconds.sum().round(1),
        "duplicate lines": ok.groupby("parser").dup_ratio.mean().round(3),
        "CER (eurotext)": ok[ok.document == "eurotext.png"].set_index("parser").cer,
    })


def by_category(df: pd.DataFrame) -> pd.DataFrame:
    d = df[df.category != ""]
    d = d.assign(v=d.field_recall.where(d.status == "ok", 0).fillna(0))
    return d.groupby(["category", "parser"]).v.mean().reset_index(name="field_recall")


def build_report(out: Path) -> Path:
    df = load(out)
    sc = scorecard(df).rename(index=label)
    html = ["<html><head><meta charset='utf-8'><title>Parser scorecard</title><style>",
            "body{font-family:system-ui;margin:24px;max-width:1100px}table{border-collapse:collapse;font-size:13px}",
            "td,th{border:1px solid #ccc;padding:4px 8px}th{background:#eee}</style></head><body>",
            "<h1>Parser scorecard</h1>",
            f"<p>{df.document.nunique()} documents × {df.parser.nunique()} parsers. Scores are 0–1; "
            "a file a parser can't open or fails on scores 0 on that file.</p>",
            sc.to_html(na_rep="–"), "<h2>Dimensions</h2><ul>",
            *[f"<li><b>{k}</b>: {v[2]}</li>" for k, v in DIMENSIONS.items()],
            "<li><b>Coverage</b>: share of files parsed without error</li>",
            f"<li><b>Speed</b>: median seconds/page on a log scale ({SPEED_BEST}s = 1, {SPEED_WORST}s = 0)</li></ul>",
            "<h2>Facts</h2>", facts(df).rename(index=label).to_html(na_rep="–"),
            "<h2>All runs</h2>", df.to_html(index=False, na_rep=""), "</body></html>"]
    p = out / "report.html"
    p.write_text("\n".join(html))
    return p
