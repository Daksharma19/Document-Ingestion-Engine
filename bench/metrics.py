"""Per-document scores for one parser output against a .labels.json (and optional .gt.txt).

Each score answers one question a reader of the report cares about:

  field_recall   Did the parser get the content?          share of labelled values present in the output
  table_rows     Did it keep table rows together?          share of labelled rows whose cells sit on one output line
  reading_order  Did it read the page in the right order?  share of consecutive labelled snippets that appear in order
  headings       Did it mark document structure?           share of labelled headings emitted as Markdown headings
  word_f1        How exact is the text?                    word-level F1 against the full transcript (.gt.txt), order-free
  cer / wer      (same, order-sensitive)                   edit distance against the transcript
  dup_ratio      Is the output clean?                      share of substantial lines that are repeated
"""
from __future__ import annotations

import json
import re
from pathlib import Path


def norm(s: str) -> str:
    s = s.lower().replace("**", "").replace("|", " ").replace("’", "'").replace("“", '"').replace("”", '"')
    return re.sub(r"\s+", " ", s).strip()


def leaf_values(obj) -> list[str]:
    if isinstance(obj, dict):
        return [v for x in obj.values() for v in leaf_values(x)]
    if isinstance(obj, list):
        return [v for x in obj for v in leaf_values(x)]
    if obj is None or isinstance(obj, bool):
        return []
    return [str(obj)]


def value_found(value: str, text_n: str, text_digits: str) -> bool:
    v = norm(value)
    if v in text_n:
        return True
    d = re.sub(r"\D", "", value)
    return len(d) >= 6 and d in text_digits  # long ids / dates written with different separators


def field_recall(text: str, expected) -> tuple[float | None, int, list[str]]:
    vals = leaf_values(expected)
    if not vals:
        return None, 0, []
    tn, td = norm(text), re.sub(r"\D", "", text)
    missing = [v for v in vals if not value_found(v, tn, td)]
    return 1 - len(missing) / len(vals), len(vals), missing


def table_rows(text: str, rows: list[list[str]]) -> float | None:
    if not rows:
        return None
    lines = [norm(l) for l in text.splitlines()]
    ok = sum(any(all(norm(c) in l for c in row) for l in lines) for row in rows)
    return ok / len(rows)


def reading_order(text: str, snippets: list[str]) -> float | None:
    """Missing snippets count as out of order, so a parser can't score well by dropping text."""
    if len(snippets) < 2:
        return None
    tn = norm(text)
    pos = [tn.find(norm(s)) for s in snippets]
    good = sum(1 for a, b in zip(pos, pos[1:]) if a >= 0 and b > a)
    return good / (len(snippets) - 1)


def headings(text: str, heads: list[str]) -> float | None:
    if not heads:
        return None
    hl = [norm(l.lstrip("#")) for l in text.splitlines() if l.lstrip().startswith("#")]
    return sum(any(norm(h) in l for l in hl) for h in heads) / len(heads)


def dup_ratio(text: str) -> float:
    lines = [norm(l) for l in text.splitlines() if len(l.strip()) >= 25]
    return 0.0 if not lines else 1 - len(set(lines)) / len(lines)


def _lev(a, b) -> int:
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def error_rates(text: str, gt: str) -> tuple[float, float]:
    ref, hyp = norm(gt), norm(text)
    return (round(min(1.0, _lev(hyp, ref) / max(1, len(ref))), 4),
            round(min(1.0, _lev(hyp.split(), ref.split()) / max(1, len(ref.split()))), 4))


def word_f1(text: str, gt: str) -> float:
    from collections import Counter
    h, r = Counter(re.findall(r"\w+", norm(text))), Counter(re.findall(r"\w+", norm(gt)))
    tp = sum((h & r).values())
    if not tp:
        return 0.0
    p, rc = tp / sum(h.values()), tp / sum(r.values())
    return 2 * p * rc / (p + rc)


def score(text: str, labels: Path | None, gt: Path | None) -> dict:
    out = {"dup_ratio": round(dup_ratio(text), 4)}
    if labels:
        lab = json.loads(labels.read_text())
        r, n, miss = field_recall(text, lab.get("expected", {}))
        out.update(category=lab.get("category", ""), field_recall=r, fields_total=n, missing="; ".join(miss)[:300],
                   table_rows=table_rows(text, lab.get("rows", [])),
                   reading_order=reading_order(text, lab.get("order", [])),
                   headings=headings(text, lab.get("headings", [])))
    if gt:
        g = gt.read_text()
        out["word_f1"] = word_f1(text, g)
        out["cer"], out["wer"] = error_rates(text, g) if len(g) < 6000 else (None, None)
    return {k: (round(v, 4) if isinstance(v, float) else v) for k, v in out.items()}


def category_of(labels: Path | None) -> str:
    if not labels:
        return ""
    return json.loads(labels.read_text()).get("category", "")
