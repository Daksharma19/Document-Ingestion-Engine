"""Parser comparison dashboard — run parsers and compare them on one scorecard.

    bench/bench.sh            (or: bench/.venv/bin/streamlit run bench/dashboard.py)
"""
from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import streamlit as st

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
from parsers import available  # noqa: E402
from report import DIMENSIONS, OURS, SPEED_BEST, SPEED_WORST, build_report, by_category, facts, label, load, scorecard  # noqa: E402
from run_bench import collect, rescore, run  # noqa: E402

RESULTS = HERE / "results"
st.set_page_config("Parser comparison", layout="wide")
PALETTE = px.colors.qualitative.Safe


@st.cache_data(show_spinner=False, ttl=60)
def parser_names() -> list[str]:
    return list(available())


def runs() -> list[Path]:
    return sorted((p for p in RESULTS.glob("*") if (p / "results.csv").exists() and not p.name.startswith("_")),
                  key=lambda p: (p / "results.csv").stat().st_mtime, reverse=True)


# ---------------- sidebar ----------------
with st.sidebar:
    st.header("Run")
    folders = st.text_area("Documents (files or folders, one per line)", "test-docs")
    uploads = st.file_uploader("…or upload documents", accept_multiple_files=True)
    names = parser_names()
    chosen = st.multiselect("Parsers", names, default=names, format_func=label)
    run_name = st.text_input("Run name", datetime.now().strftime("%Y%m%d-%H%M%S"))
    go_btn = st.button("Run", type="primary", width="stretch")
    rescore_btn = st.button("Re-score after editing labels", width="stretch")
    st.caption("Ground truth lives next to each document: `<name>.labels.json` (values, table rows, reading "
               "order, headings, category) and optional `<name>.gt.txt` (full transcript). "
               "docextract appears only while the Spring API is running.")

roots = [str(ROOT / l.strip()) for l in folders.splitlines() if l.strip()]
if go_btn:
    out = RESULTS / run_name
    out.mkdir(parents=True, exist_ok=True)
    paths = list(roots)
    if uploads:
        (out / "uploads").mkdir(exist_ok=True)
        for u in uploads:
            (out / "uploads" / u.name).write_bytes(u.getbuffer())
        paths.append(str(out / "uploads"))
    docs = collect(paths)
    parsers = {k: v for k, v in available().items() if k in chosen}
    bar, n = st.progress(0.0, "starting…"), [0]

    def tick(row):
        n[0] += 1
        bar.progress(n[0] / max(1, len(docs) * len(parsers)), f"{label(row['parser'])} · {row['document']}")

    run(docs, parsers, out, on_row=tick)
    build_report(out)
    st.session_state["run"] = out.name
    st.rerun()

all_runs = runs()
if not all_runs:
    st.info("No results yet. Pick documents and parsers in the sidebar and press **Run**.")
    st.stop()
run_names = [r.name for r in all_runs]
sel = st.selectbox("Results", run_names,
                   index=run_names.index(st.session_state["run"]) if st.session_state.get("run") in run_names else 0)
OUT = RESULTS / sel
if rescore_btn:
    rescore(OUT, roots + [str(ROOT / "test-docs"), str(OUT / "uploads")])
    build_report(OUT)

df = load(OUT)
sc = scorecard(df)
parsers_sorted = list(sc.index)
COLOR = {p: PALETTE[i % len(PALETTE)] for i, p in enumerate(sorted(df.parser.unique()))}
CMAP = {label(p): c for p, c in COLOR.items()}
DIMS = list(DIMENSIONS) + ["Coverage", "Speed"]

st.title("Parser comparison")
st.caption(f"{df.document.nunique()} documents · {df.parser.nunique()} parsers · ★ = our system. "
           "**docextract** is the full engine (Spring `/api/parse`: text layer + layout + OCR fallback); "
           "**ocr-service** is only its OCR stage (RapidOCR / PP-OCR) run on every page.")

tabs = st.tabs(["Scorecard", "Where each one wins", "Speed vs quality", "Per document", "Read the outputs",
                "How it's scored"])

# ---------- scorecard ----------
with tabs[0]:
    show = sc[DIMS + ["Overall"]].rename(index=label)
    st.dataframe(show.style.background_gradient(cmap="RdYlGn", vmin=0, vmax=1, axis=None).format("{:.2f}", na_rep="–"),
                 width="stretch")
    st.caption("Overall = mean of the six quality dimensions and coverage. Speed is shown separately.")

    default = [p for p in parsers_sorted if p in OURS][:1] + [p for p in parsers_sorted if p not in OURS][:2]
    pick = st.multiselect("Compare on the radar", parsers_sorted, default=default, format_func=label)
    fig = go.Figure()
    for p in pick:
        vals = sc.loc[p, DIMS].fillna(0).tolist()
        fig.add_trace(go.Scatterpolar(r=vals + vals[:1], theta=DIMS + DIMS[:1], name=label(p), fill="toself",
                                      opacity=0.55, line=dict(color=COLOR[p], width=3 if p in OURS else 1.5)))
    fig.update_layout(polar=dict(radialaxis=dict(range=[0, 1])), height=520, legend=dict(orientation="h", y=-0.1))
    st.plotly_chart(fig, width="stretch")

    long = sc[DIMS].reset_index().melt("parser", var_name="dimension", value_name="score")
    long["parser"] = long.parser.map(label)
    st.plotly_chart(px.bar(long, x="dimension", y="score", color="parser", barmode="group", range_y=[0, 1.05],
                           color_discrete_map=CMAP, title="Each dimension, side by side", height=460)
                    .update_layout(legend=dict(orientation="h", y=-0.25)), width="stretch")

# ---------- by category ----------
with tabs[1]:
    st.markdown("Content found (field recall) per **kind of document**. This is where the parsers separate: "
                "text-layer libraries are perfect on digital PDFs and score zero on scans.")
    cat = by_category(df)
    cat["parser"] = cat.parser.map(label)
    counts = df[df.category != ""].groupby("category").document.nunique()
    cat["category"] = cat.category.map(lambda c: f"{c} ({counts[c]})")
    st.plotly_chart(px.bar(cat, x="category", y="field_recall", color="parser", barmode="group", range_y=[0, 1.05],
                           color_discrete_map=CMAP, height=480)
                    .update_layout(legend=dict(orientation="h", y=-0.2)), width="stretch")
    win = cat.loc[cat.groupby("category").field_recall.idxmax()][["category", "parser", "field_recall"]]
    st.dataframe(win.rename(columns={"parser": "best parser"}), hide_index=True, width="stretch")

# ---------- speed vs quality ----------
with tabs[2]:
    pts = sc.assign(sec=df[df.status == "ok"].groupby("parser").sec_per_page.median()).reset_index()
    pts["name"] = pts.parser.map(label)
    fig = px.scatter(pts, x="sec", y="Overall", text="name", log_x=True, color="name", color_discrete_map=CMAP,
                     labels={"sec": "median seconds per page (log)", "Overall": "overall quality"}, height=520)
    fig.update_traces(marker=dict(size=16), textposition="top center", showlegend=False)
    st.plotly_chart(fig, width="stretch")
    st.caption("Top-left is best: high quality, low latency. Times exclude model loading (one warm-up file per "
               "parser) and were measured on the same machine in one run.")
    st.dataframe(facts(df).rename(index=label), width="stretch")

# ---------- per document ----------
with tabs[3]:
    metric = st.radio("Measure", ["field_recall", "table_rows", "reading_order", "headings", "word_f1"],
                      horizontal=True, format_func=lambda m: m.replace("_", " "))
    d = df.assign(v=df[metric].where(df.status == "ok"))
    has = d.groupby("document")[metric].transform(lambda s: s.notna().any())
    piv = d[has].pivot_table(index=["category", "document"], columns="parser", values="v", aggfunc="first",
                             dropna=False)
    piv = piv.rename(columns=label).reindex(columns=[label(p) for p in parsers_sorted])
    piv.index = [f"{c} · {doc[:40]}" for c, doc in piv.index]
    if piv.empty:
        st.info("No document in this run has that check.")
    else:
        st.plotly_chart(px.imshow(piv, zmin=0, zmax=1, color_continuous_scale="RdYlGn", text_auto=".2f", aspect="auto",
                                  height=max(380, 26 * len(piv) + 160))
                        .update_xaxes(side="top"), width="stretch")
        st.caption("Blank = parser can't open that file type or failed.")
    miss = df[(df.status == "ok") & df.missing.fillna("").ne("")][["parser", "document", "field_recall", "missing"]]
    miss["parser"] = miss.parser.map(label)
    with st.expander(f"What each parser missed ({len(miss)} rows)"):
        st.dataframe(miss.sort_values(["document", "parser"]), hide_index=True, width="stretch")

# ---------- outputs ----------
with tabs[4]:
    doc = st.selectbox("Document", sorted(df.document.unique()))
    ps = [p for p in parsers_sorted if ((df.document == doc) & (df.parser == p) & (df.status == "ok")).any()]
    show_p = st.multiselect("Parsers", ps, default=ps[:3], format_func=label)
    src = next((p for p in collect([str(ROOT / "test-docs"), str(OUT / "uploads")]) if p.name == doc), None)
    if src and src.suffix.lower() in {".png", ".jpg", ".jpeg"}:
        st.image(str(src), width=520)
    rows = df[df.document == doc].set_index("parser")
    for col, p in zip(st.columns(max(1, len(show_p))), show_p):
        r = rows.loc[p]
        col.markdown(f"**{label(p)}**  \n{r.seconds:.2f}s · recall {r.field_recall if pd.notna(r.field_recall) else '–'}")
        f = OUT / "outputs" / p / f"{doc}.txt"
        col.text_area(p, f.read_text(errors="replace") if f.exists() else "", height=600,
                      label_visibility="collapsed", key=f"{doc}-{p}")

# ---------- method ----------
with tabs[5]:
    st.markdown("\n".join(
        [f"- **{k}**: {v[2]}" for k, v in DIMENSIONS.items()] +
        ["- **Coverage**: share of all files parsed without error",
         f"- **Speed**: median seconds per page on a log scale ({SPEED_BEST}s → 1, {SPEED_WORST}s → 0)",
         "", "Every quality score is the mean over the documents that carry that check. A file a parser can't open "
             "or fails on counts as 0, because a user who hands it that file gets nothing back.",
         "", "Ground truth was written by hand from the rendered pages. Simulated scans in `test-docs/scanned/` are "
             "page 1 of labelled digital PDFs rendered at 200 dpi with skew, blur, noise and JPEG compression; their "
             "transcripts are the original PDF's own text layer."]))
    st.download_button("results.csv", (OUT / "results.csv").read_bytes(), f"{sel}.csv")
    if (OUT / "report.html").exists():
        st.download_button("Static report.html", (OUT / "report.html").read_bytes(), f"{sel}.html")
