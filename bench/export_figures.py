"""Export the scorecard charts as PNG files for the written report.

    bench/.venv/bin/python bench/export_figures.py [RESULT_DIR] [OUT_DIR]

Defaults: bench/results/test-docs -> bench/figures/. Writes 300 dpi PNGs plus scorecard.csv.
"""
from __future__ import annotations

import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import DIMENSIONS, OURS, by_category, facts, load, scorecard  # noqa: E402

DIMS = list(DIMENSIONS) + ["Coverage", "Speed"]
NAMES = {"docextract": "docextract (ours)", "rapidocr-engine": "ocr-service (ours, OCR only)"}
COLORS = {"docextract": "#1f6feb", "rapidocr-engine": "#79b8ff", "docling": "#d1495b", "tesseract": "#edae49",
          "pypdf": "#66a182", "pdfplumber": "#8d6a9f", "pymupdf": "#7a7a7a"}
plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False, "savefig.dpi": 300})

# docextract before the three layout fixes (same documents and labels, run of 2026-11-24)
BEFORE = {"Content (digital)": 0.986, "Content (scanned)": 0.991, "Word accuracy": 0.994, "Table rows": 0.861,
          "Reading order": 0.944, "Headings": 0.545, "Coverage": 1.0, "Speed": 0.408}


def name(p):
    return NAMES.get(p, p)


def save(fig, out: Path, file: str) -> Path:
    p = out / file
    fig.savefig(p, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    return p


def heatmap(sc, out):
    data = sc[DIMS + ["Overall"]]
    fig, ax = plt.subplots(figsize=(11, 0.55 * len(data) + 1.6))
    im = ax.imshow(data.values, cmap="RdYlGn", vmin=0, vmax=1, aspect="auto")
    ax.set_xticks(range(data.shape[1]), data.columns, rotation=30, ha="right")
    ax.set_yticks(range(len(data)), [name(p) for p in data.index])
    for lbl in ax.get_yticklabels():
        if any(lbl.get_text() == name(o) for o in OURS):
            lbl.set_fontweight("bold")
    for i in range(data.shape[0]):
        for j in range(data.shape[1]):
            v = data.values[i, j]
            ax.text(j, i, "–" if np.isnan(v) else f"{v:.2f}", ha="center", va="center", fontsize=9,
                    color="black" if np.isnan(v) or 0.3 < v < 0.85 else "white")
    ax.axvline(len(DIMS) - 0.5, color="white", lw=3)
    fig.colorbar(im, ax=ax, fraction=0.025, pad=0.02, label="score (0–1)")
    ax.set_title("Parser scorecard (higher is better)", loc="left", fontweight="bold")
    ax.spines[:].set_visible(False)
    return save(fig, out, "01_scorecard.png")


def radar(sc, out, parsers):
    ang = np.linspace(0, 2 * np.pi, len(DIMS), endpoint=False).tolist()
    fig, ax = plt.subplots(figsize=(7, 7), subplot_kw={"polar": True})
    for p in parsers:
        v = sc.loc[p, DIMS].fillna(0).tolist()
        ax.plot(ang + ang[:1], v + v[:1], color=COLORS.get(p), lw=2.6 if p in OURS else 1.6, label=name(p))
        ax.fill(ang + ang[:1], v + v[:1], color=COLORS.get(p), alpha=0.12)
    ax.set_xticks(ang, DIMS)
    ax.set_ylim(0, 1)
    ax.set_yticks([0.25, 0.5, 0.75, 1.0], ["0.25", "0.5", "0.75", "1"], fontsize=8, color="grey")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.06), ncol=2, frameon=False)
    ax.set_title("Capability profile", fontweight="bold", pad=20)
    return save(fig, out, "02_radar.png")


def dimension_bars(sc, out):
    order = list(sc.index)
    x = np.arange(len(DIMS))
    w = 0.8 / len(order)
    fig, ax = plt.subplots(figsize=(13, 4.8))
    for k, p in enumerate(order):
        ax.bar(x + (k - (len(order) - 1) / 2) * w, sc.loc[p, DIMS].fillna(0), w, color=COLORS.get(p), label=name(p),
               edgecolor="black" if p in OURS else "none", linewidth=0.8)
    ax.set_xticks(x, DIMS, rotation=20, ha="right")
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("score")
    ax.legend(ncol=4, frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.2))
    ax.set_title("Each dimension, side by side", loc="left", fontweight="bold")
    return save(fig, out, "03_dimensions.png")


def categories(df, sc, out):
    cat = by_category(df).pivot(index="category", columns="parser", values="field_recall")
    counts = df[df.category != ""].groupby("category").document.nunique()
    order = [p for p in sc.index if p in cat.columns]
    x = np.arange(len(cat))
    w = 0.8 / len(order)
    fig, ax = plt.subplots(figsize=(12, 4.6))
    for k, p in enumerate(order):
        ax.bar(x + (k - (len(order) - 1) / 2) * w, cat[p].fillna(0), w, color=COLORS.get(p), label=name(p),
               edgecolor="black" if p in OURS else "none", linewidth=0.8)
    ax.set_xticks(x, [f"{c}\n({counts[c]} docs)" for c in cat.index])
    ax.set_ylim(0, 1.05)
    ax.set_ylabel("labelled values found")
    ax.legend(ncol=4, frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.16))
    ax.set_title("Content found, by kind of document", loc="left", fontweight="bold")
    return save(fig, out, "04_by_category.png")


def speed_quality(df, sc, out):
    sec = df[df.status == "ok"].groupby("parser").sec_per_page.median()
    fig, ax = plt.subplots(figsize=(8, 5.2))
    for p in sc.index:
        ax.scatter(sec[p], sc.loc[p, "Overall"], s=180 if p in OURS else 110, color=COLORS.get(p),
                   edgecolor="black" if p in OURS else "none", zorder=3)
        ax.annotate(name(p), (sec[p], sc.loc[p, "Overall"]), xytext=(8, -14 if p == "pypdf" else 6),
                    textcoords="offset points", fontsize=9)
    ax.set_xscale("log")
    ax.set_xlabel("median seconds per page (log scale, lower is better)")
    ax.set_ylabel("overall quality (0–1)")
    ax.set_ylim(0, 1.05)
    ax.grid(alpha=0.3)
    ax.set_title("Quality vs speed (top-left is best)", loc="left", fontweight="bold")
    return save(fig, out, "05_speed_vs_quality.png")


def before_after(sc, out):
    after = sc.loc["docextract", DIMS]
    x = np.arange(len(DIMS))
    fig, ax = plt.subplots(figsize=(11, 4.2))
    ax.bar(x - 0.2, [BEFORE[d] for d in DIMS], 0.4, color="#b0c4de", label="before fixes")
    ax.bar(x + 0.2, after.fillna(0), 0.4, color=COLORS["docextract"], label="after fixes")
    for i, d in enumerate(DIMS):
        delta = after[d] - BEFORE[d]
        if abs(delta) >= 0.005:
            ax.text(i + 0.2, after[d] + 0.02, f"{delta:+.2f}", ha="center", fontsize=8,
                    color="#1a7f37" if delta > 0 else "#cf222e")
    ax.set_xticks(x, DIMS, rotation=20, ha="right")
    ax.set_ylim(0, 1.12)
    ax.legend(frameon=False, ncol=2, loc="upper right")
    ax.set_title("docextract: effect of the layout fixes", loc="left", fontweight="bold")
    return save(fig, out, "06_docextract_before_after.png")


def main():
    res = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "results" / "test-docs"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).parent / "figures"
    out.mkdir(parents=True, exist_ok=True)
    df = load(res)
    sc = scorecard(df)
    sc.rename(index=name).to_csv(out / "scorecard.csv")
    facts(df).rename(index=name).to_csv(out / "facts.csv")
    top = [p for p in ["docextract", "docling", "tesseract", "pymupdf"] if p in sc.index]
    files = [heatmap(sc, out), radar(sc, out, top), dimension_bars(sc, out), categories(df, sc, out),
             speed_quality(df, sc, out)]
    if "docextract" in sc.index:
        files.append(before_after(sc, out))
    print("\n".join(map(str, files)))


if __name__ == "__main__":
    main()
