"""Page rendering, image clean-up and recognition (RapidOCR / PP-OCR models on ONNX Runtime)."""
from __future__ import annotations

import io
import logging
import queue
import math
import os
import statistics
import threading
import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
import pypdfium2 as pdfium

from layout import Box, to_lines, to_text

PDF_MAGIC = b"%PDF"
log = logging.getLogger("docextract.ocr")


# PDFium is not thread-safe: concurrent requests rendering at once produced pages with missing glyphs and could
# crash the process (0xC000001D in a pypdfium2 finaliser). Every PDFium call, including closing, holds this lock.
_PDFIUM_LOCK = threading.Lock()


def render_pages(data: bytes, dpi: int, max_pages: int, only: list[int] | None = None) -> list[np.ndarray]:
    """PDF pages (rendered at `dpi`) or a single image, as BGR arrays. `only` (0-based page indexes) renders just
    those PDF pages, in that order: the Java engine OCRs only the pages its planner sent here."""
    if data[:4] == PDF_MAGIC:
        with _PDFIUM_LOCK:
            pdf = pdfium.PdfDocument(data)
            try:
                pages = []
                wanted = [i for i in only if 0 <= i < len(pdf)] if only else range(min(len(pdf), max_pages))
                for i in wanted:
                    page = pdf[i]
                    bitmap = page.render(scale=dpi / 72)
                    pages.append(cv2.cvtColor(np.asarray(bitmap.to_pil().convert("RGB")), cv2.COLOR_RGB2BGR))
                    bitmap.close()
                    page.close()
                return pages
            finally:
                pdf.close()
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        # Multi-page TIFF and other formats OpenCV cannot decode in one call.
        from PIL import Image, ImageSequence
        try:
            pil = Image.open(io.BytesIO(data))
        except Exception as e:  # noqa: BLE001 - surfaced to the caller as 415
            raise ValueError(f"not a PDF or a readable image: {e}") from e
        return [cv2.cvtColor(np.asarray(f.convert("RGB")), cv2.COLOR_RGB2BGR)
                for f in list(ImageSequence.Iterator(pil))[:max_pages]]
    return [img]


def speckle_ratio(gray: np.ndarray) -> float:
    """Share of dark connected components that are speck-sized. Clean renders sit near 0; photocopies and
    fax-like scans are dominated by specks. Same signal as the Java ImagePreprocessor.isNoisy."""
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    n, _, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    if n <= 1:
        return 0.0
    areas = stats[1:, cv2.CC_STAT_AREA]
    return float(np.count_nonzero(areas <= 4)) / len(areas)


UPSCALE_TO = 1800


def clean(img: np.ndarray, stats: dict, upscale: bool = True) -> np.ndarray:
    """Only touches pages that need it: cleaning a crisp render costs accuracy."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    # Small images (phone crops, low-DPI scans): PP-OCR recognises text best at ~30-40 px line height.
    # 1800 px, not 1200: on a real 72 DPI scan (612x792) 1200 left 14 misread words, 1800 none (2400: same).
    if upscale and max(h, w) < UPSCALE_TO:
        scale = UPSCALE_TO / max(h, w)
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        stats["upscaled"] = round(scale, 2)
    ratio = speckle_ratio(gray)
    stats["speckle"] = round(ratio, 3)
    if ratio > 0.25:
        # PP-OCR tolerates noise well; a 3x3 median removes specks cheaply (non-local means cost 7 s/page, no gain).
        gray = cv2.medianBlur(gray, 3)
        stats["denoised"] = True
        img = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    # Uneven lighting (phone photos, no true white anywhere): flatten the background before recognition.
    # Renders and flatbed scans have a white page and are left alone (flattening would erase dark header bars).
    if float(np.percentile(gray, 95)) < 225:
        bg = cv2.medianBlur(gray, 51)
        norm = cv2.divide(gray, bg, scale=255)
        img = cv2.cvtColor(norm, cv2.COLOR_GRAY2BGR)
        stats["flattened"] = True
    return img


def unrotate(quads, angle_deg: float, w: int, h: int):
    """Rotates box corners by -angle around the page centre. Recognition already reads skewed crops correctly,
    so only the geometry used for line grouping needs straightening; no second OCR pass."""
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle_deg, 1.0)
    out = []
    for q in quads:
        pts = np.hstack([np.asarray(q, dtype=np.float64), np.ones((len(q), 1))])
        out.append((pts @ m.T).tolist())
    return out


def skew_of(quads) -> float:
    """Median baseline angle (degrees) of the wider detected text boxes."""
    angles = []
    for q in quads:
        (x0, y0), (x1, y1) = q[0], q[1]
        if x1 - x0 > 60:
            angles.append(math.degrees(math.atan2(y1 - y0, x1 - x0)))
    return statistics.median(angles) if len(angles) >= 3 else 0.0


def split_points(crop: np.ndarray, max_ratio: float) -> list[int]:
    """Where to cut a long text-line crop so that no piece is much wider than `max_ratio` x its height.

    PP-OCR's recogniser has global attention, so its cost grows with the square of line length: ten 48:1 lines
    take 4.5 s whole and 2.4 s in quarters. Cuts go only through ink-free column runs (word gaps), so no glyph is
    ever sliced; a stretch with no gap near the target is simply left longer."""
    h, w = crop.shape[:2]
    target = max_ratio * h
    if w <= 1.3 * target:
        return []
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
    _, ink = cv2.threshold(gray, 0, 1, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    empty = ink.sum(axis=0) <= max(1, h // 30)
    # Midpoints of empty runs as wide as a word space. Letter gaps in bold or loosely kerned text reach ~0.2 x the
    # crop height; cutting there split words ("undergrad uate", "De lhi"), so only gaps >= 0.35 x height qualify.
    gaps, start, min_run = [], None, max(3, int(0.35 * h))
    for x, e in enumerate(np.append(empty, False)):
        if e and start is None:
            start = x
        elif not e and start is not None:
            if x - start >= min_run:
                gaps.append((start + x) // 2)
            start = None
    cuts, last = [], 0
    while w - last > 1.3 * target:
        want = last + target
        near = [g for g in gaps if last + 0.5 * target <= g <= last + 1.35 * target]
        if not near:
            later = [g for g in gaps if g > last + 1.35 * target and w - g > 0.3 * target]
            if not later:
                break
            near = [later[0]]
        cut = min(near, key=lambda g: abs(g - want))
        cuts.append(cut)
        last = cut
    return cuts


GPU_PROVIDERS = {"dml": "DmlExecutionProvider", "cuda": "CUDAExecutionProvider"}


def device_params(device: str, gpu_id: int) -> dict:
    """RapidOCR overrides that put ONNX Runtime on `device` (cpu | dml | cuda)."""
    if device == "dml":  # onnxruntime-directml: any DirectX 12 GPU. Adapter 0 is the Windows default GPU.
        return {"EngineConfig.onnxruntime.use_dml": True, "EngineConfig.onnxruntime.dml_ep_cfg": {"device_id": gpu_id}}
    if device == "cuda":  # onnxruntime-gpu plus the CUDA / cuDNN runtime DLLs it was built for
        # HEURISTIC, not RapidOCR's EXHAUSTIVE: exhaustive search re-benchmarks cuDNN for every new input shape, and
        # every page size is a new shape, so each new document paid for a search.
        return {"EngineConfig.onnxruntime.use_cuda": True, "EngineConfig.onnxruntime.cuda_ep_cfg.device_id": gpu_id,
                "EngineConfig.onnxruntime.cuda_ep_cfg.cudnn_conv_algo_search": "HEURISTIC"}
    return {}


def warmup_image() -> np.ndarray:
    """A tiny page with two lines of text: enough to make RapidOCR load and run both models."""
    img = np.full((160, 640, 3), 255, np.uint8)
    cv2.putText(img, "Invoice No 12345", (20, 60), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 2)
    cv2.putText(img, "Total 1,234.56 USD", (20, 130), cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 0, 0), 2)
    return img


class OcrEngine:
    def __init__(self, det_limit: int = 1600, device: str | None = None, gpu_id: int | None = None) -> None:
        # OCR_DEVICE: auto (default: the GPU provider the installed onnxruntime package offers, else CPU) | dml |
        # cuda | cpu. OCR_GPU_ID picks the adapter (DirectML: 0 = Windows default GPU; CUDA: nvidia-smi index).
        device = (device or os.environ.get("OCR_DEVICE", "auto")).strip().lower()
        if device not in ("auto", "cpu", *GPU_PROVIDERS):
            raise ValueError(f"OCR_DEVICE must be auto, cpu, dml or cuda, not {device!r}")
        self._det_limit = int(os.environ.get("OCR_DET_LIMIT", det_limit))
        # OCR_REC_MODEL: latin (default) = the bundled PP-OCRv6 small recogniser with its output layer cut to Latin
        # script (identical text on Latin documents, ~17% faster; see trim_model.py) | latin-tiny = the same cut of
        # PP-OCRv6 tiny | full = small with all 18,710 characters (CJK etc.).
        self._rec_model = self._rec_model_path(os.environ.get("OCR_REC_MODEL", "latin").strip().lower())
        # OCR_REC_WORKERS: CPU recognisers run side by side (ONNX Runtime keeps only ~half the cores busy on
        # one session's small batches). Default: half the logical CPUs, at most 4.
        cpus = os.cpu_count() or 2
        self._rec_workers = max(1, int(os.environ.get("OCR_REC_WORKERS", max(1, min(4, cpus // 2)))))
        self._rec_threads = max(1, cpus // self._rec_workers)
        # Long text lines are recognised in pieces of at most ~OCR_SPLIT_RATIO x their height (0 = never split).
        self._split_ratio = float(os.environ.get("OCR_SPLIT_RATIO", "12"))
        self._gpu_id = int(os.environ.get("OCR_GPU_ID", "0")) if gpu_id is None else gpu_id
        # OCR_GPU_STAGES: det (default) runs only text detection on the GPU, all also runs recognition there.
        # Recognition is many small batches of variable width; on an integrated GPU (DirectML) the per-call
        # overhead and per-shape recompiles make it slower than the CPU, while detection (one big image) wins.
        self._gpu_stages = os.environ.get("OCR_GPU_STAGES", "det").strip().lower()
        if self._gpu_stages not in ("det", "all"):
            raise ValueError(f"OCR_GPU_STAGES must be det or all, not {self._gpu_stages!r}")
        if device == "auto":
            import onnxruntime as ort
            have = ort.get_available_providers()
            candidates = [d for d, p in GPU_PROVIDERS.items() if p in have] + ["cpu"]
        else:
            candidates = [device, "cpu"]
        # Detection runs one page at a time (it is one big image, on the GPU when there is one); recognition
        # pieces go to a pool of recognisers, so a page's recognition overlaps the next page's detection.
        self._det_lock = threading.Lock()
        self._reload_lock = threading.Lock()
        self._page_pool = ThreadPoolExecutor(2, thread_name_prefix="ocr-page")
        # OCR_MAX_CONCURRENT documents are processed at once (default 2: one detecting while one is recognised);
        # the rest queue. More in flight only oversubscribes the CPU: 6 dense PDFs at once finished later than
        # the same 6 one after another.
        self._admit = threading.Semaphore(max(1, int(os.environ.get("OCR_MAX_CONCURRENT", "2"))))
        for dev in dict.fromkeys(candidates):
            if self._load(dev):
                break
        else:
            raise RuntimeError("no ONNX Runtime execution provider could run the OCR models")

    def _load(self, device: str) -> bool:
        """Builds RapidOCR on `device` and runs one tiny page through it, since RapidOCR creates its ONNX sessions
        lazily and a GPU provider can fail at session creation or on the first run."""
        try:
            ocr = self._build(device)
            if device != "cpu" and self._gpu_stages == "det":
                cpu = self._build("cpu")
                cpu(warmup_image())
                ocr.text_rec = cpu.text_rec  # RapidOCR loads models lazily, so its own rec model is never built
            ocr(warmup_image())
            det = list(ocr.text_det.session.session.get_providers())
            rec = list(ocr.text_rec.session.session.get_providers())
        except Exception as e:  # noqa: BLE001 - a broken GPU stack must not take the service down
            log.warning("OCR device %s failed (%s: %s); trying the next one", device, type(e).__name__, e)
            return False
        if device != "cpu" and det[0] != GPU_PROVIDERS[device]:
            # ONNX Runtime silently drops a GPU provider whose DLLs or driver are missing.
            log.warning("OCR device %s is not usable (ONNX Runtime chose %s); trying the next one", device, det)
            return False
        recs = [ocr.text_rec]
        if rec[0] == "CPUExecutionProvider" and self._rec_workers > 1:
            recs = []
            for _ in range(self._rec_workers):
                worker = self._build("cpu", threads=self._rec_threads)
                worker(warmup_image())
                recs.append(worker.text_rec)
        self._recs: queue.Queue = queue.Queue()
        for r in recs:
            self._recs.put(r)
        self._rec_pool = ThreadPoolExecutor(len(recs), thread_name_prefix="ocr-rec")
        self._n_recs = len(recs)
        self._ocr, self.device = ocr, device
        self.providers = {"det": det[0], "rec": rec[0], "recWorkers": len(recs),
                          "recModel": Path(self._rec_model).stem if self._rec_model else "full", "detLimit": self._det_limit}
        log.info("OCR running on %s (det %s, rec %s x%d)", device, det[0], rec[0], len(recs))
        return True

    @staticmethod
    def _rec_model_path(choice: str) -> str | None:
        if choice not in ("latin", "latin-tiny", "full"):
            raise ValueError(f"OCR_REC_MODEL must be latin, latin-tiny or full, not {choice!r}")
        if choice == "full":
            return None
        try:
            from trim_model import ensure_latin_model
            return str(ensure_latin_model("tiny" if choice == "latin-tiny" else "small"))
        except Exception as e:  # noqa: BLE001 - missing `onnx` package or a model layout change: use the full one
            log.warning("could not build the Latin recognition model (%s: %s); using the full one", type(e).__name__, e)
            return None

    def _build(self, device: str, threads: int | None = None):
        from rapidocr import RapidOCR
        if device == "cuda":
            import onnxruntime as ort
            if hasattr(ort, "preload_dlls"):
                ort.preload_dlls()  # finds CUDA/cuDNN installed as pip wheels (onnxruntime-gpu[cuda,cudnn])
        from rapidocr.inference_engine.onnxruntime.provider_config import ProviderConfig
        if not getattr(ProviderConfig.dml_ep_cfg, "_plain", False):
            # RapidOCR 3.10 hands dml_ep_cfg to ONNX Runtime as an OmegaConf node, which ORT rejects.
            original = ProviderConfig.dml_ep_cfg
            ProviderConfig.dml_ep_cfg = lambda cfg: dict(original(cfg))
            ProviderConfig.dml_ep_cfg._plain = True
        # Text *detection* runs on a copy whose long side is det_limit px (it dominates the run time: ~9 s vs ~2 s
        # per A4 page at 300 dpi); *recognition* crops each line from the full-resolution page (max_side_len),
        # so small print stays sharp.
        return RapidOCR(params={"Global.max_side_len": 4000, "Global.log_level": "warning",
                                "Det.limit_type": "max", "Det.limit_side_len": self._det_limit,
                                # The 180-degree line classifier flips some upright lines of dense
                                # justified text into garbage; document pages are upright.
                                "Global.use_cls": False,
                                **({"Rec.model_path": self._rec_model} if self._rec_model else {}),
                                **({"EngineConfig.onnxruntime.intra_op_num_threads": threads} if threads else {}),
                                **device_params(device, self._gpu_id)})

    def _run(self, img: np.ndarray):
        try:
            return self._detect_and_read(img)
        except Exception as e:  # noqa: BLE001
            if self.device == "cpu":
                raise
            # A GPU can stop mid-service (driver reset, DXGI device removed): finish on the CPU from now on.
            log.warning("OCR on %s failed (%s: %s); switching to CPU", self.device, type(e).__name__, e)
            with self._reload_lock:
                if self.device != "cpu" and not self._load("cpu"):
                    raise
            return self._detect_and_read(img)

    def _recognise(self, pieces: list[np.ndarray]) -> list[tuple[str, float]]:
        """Recognition of all pieces, split into width-balanced chunks across the recogniser pool."""
        from rapidocr.ch_ppocr_rec import TextRecInput

        def run(idx: list[int]):
            rec = self._recs.get()
            try:
                out = rec(TextRecInput(img=[pieces[i] for i in idx]))
            finally:
                self._recs.put(rec)
            return idx, list(zip(out.txts, out.scores))

        n = min(self._n_recs, len(pieces))
        chunks: list[list[int]] = [[] for _ in range(n)]
        load = [0.0] * n
        # Longest first onto the lightest chunk: cost grows with width, so balance by width, not count.
        for i in sorted(range(len(pieces)), key=lambda i: -pieces[i].shape[1] / max(1, pieces[i].shape[0])):
            k = load.index(min(load))
            chunks[k].append(i)
            load[k] += pieces[i].shape[1] / max(1, pieces[i].shape[0])
        results: list[tuple[str, float]] = [("", 0.0)] * len(pieces)
        for idx, res in self._rec_pool.map(run, [c for c in chunks if c]):
            for i, r in zip(idx, res):
                results[i] = r
        return results

    def _detect_and_read(self, img: np.ndarray):
        """Detection, then recognition of every line in one batch, with long lines cut at word gaps first
        (see split_points; OCR_SPLIT_RATIO=0 turns splitting off). Pieces of a line are rejoined with a space,
        so callers see one box per detected line, exactly as before."""
        from rapidocr.utils.process_img import get_rotate_crop_image
        with self._det_lock:
            det = self._ocr(img, use_det=True, use_cls=False, use_rec=False)
        if det.boxes is None or len(det.boxes) == 0:
            return [], []
        quads = [np.asarray(q, dtype=np.float32) for q in det.boxes]
        pieces, owner = [], []
        for i, q in enumerate(quads):
            crop = get_rotate_crop_image(img, q.copy())
            edges = [0, *(split_points(crop, self._split_ratio) if self._split_ratio > 0 else []), crop.shape[1]]
            for a, b in zip(edges, edges[1:]):
                pieces.append(crop[:, a:b])
                owner.append(i)
        texts: list[list[str]] = [[] for _ in quads]
        weighted = [[0.0, 0] for _ in quads]
        for i, (t, s) in zip(owner, self._recognise(pieces)):
            t = t.strip()
            if t:
                texts[i].append(t)
                weighted[i][0] += float(s) * len(t)
                weighted[i][1] += len(t)
        items = [(" ".join(ts), w / n if n else 0.0) for ts, (w, n) in zip(texts, weighted)]
        return [q.tolist() for q in quads], items

    def recognise_page(self, img: np.ndarray, min_score: float, upscale: bool = True) -> dict:
        stats: dict = {}
        img = clean(img, stats, upscale)
        quads, items = self._run(img)
        h, w = img.shape[:2]
        angle = skew_of(quads)
        if abs(angle) > 0.4:
            # Skewed scan: lines would merge or split when grouped by height.
            quads = unrotate(quads, angle, w, h)
            stats["deskewed"] = round(angle, 2)
        boxes = [Box.from_quad(q, t.strip(), s) for q, (t, s) in zip(quads, items) if t.strip() and s >= min_score]
        # Vertical text (arXiv margin stamps, rotated table headers) is read as if horizontal and comes out as
        # junk ("a 00[.] 1.:0002"): a box of 3+ characters that is twice as tall as wide is not horizontal text.
        vertical = [b for b in boxes if len(b.text) >= 3 and b.height > 2 * (b.x1 - b.x0)]
        if vertical:
            boxes = [b for b in boxes if b not in vertical]
            stats["verticalDropped"] = len(vertical)
        return {
            "width": w,
            "height": h,
            "text": to_text(boxes, h),
            "lines": to_lines(boxes),
            "meanConfidence": round(statistics.fmean(b.score for b in boxes), 4) if boxes else 0.0,
            "boxes": [{"text": b.text, "confidence": round(b.score, 4),
                       "box": [round(b.x0), round(b.y0), round(b.x1), round(b.y1)]} for b in boxes],
            "preprocess": stats,
        }

    def recognise_regions(self, data: bytes, dpi: int, regions: dict[int, list[list[float]]],
                          min_score: float = 0.5) -> dict:
        """OCR of rectangles only (PDF points, top-left origin) on the given 0-based pages: the text inside images
        on pages whose text layer is otherwise fine. Only the rectangles are rendered (PDFium clips), so a figure
        costs a fraction of a page. Boxes come back in full-page pixels at `dpi`, placed like whole-page OCR."""
        start = time.perf_counter()
        k = dpi / 72.0
        crops = []  # (page, x0_px, y0_px, image)
        if data[:4] != PDF_MAGIC:
            raise ValueError("regions need a PDF")
        with self._admit:
            with _PDFIUM_LOCK:
                pdf = pdfium.PdfDocument(data)
                try:
                    for index in sorted(regions):
                        if not 0 <= index < len(pdf):
                            continue
                        page = pdf[index]
                        pw, ph = page.get_size()
                        for x0, y0, x1, y1 in regions[index]:
                            x0, y0 = max(0.0, x0 - 2), max(0.0, y0 - 2)
                            x1, y1 = min(pw, x1 + 2), min(ph, y1 + 2)
                            if x1 - x0 < 4 or y1 - y0 < 4:
                                continue
                            # crop = points to cut from left, bottom, right, top
                            bitmap = page.render(scale=k, crop=(x0, ph - y1, pw - x1, y0))
                            img = cv2.cvtColor(np.asarray(bitmap.to_pil().convert("RGB")), cv2.COLOR_RGB2BGR)
                            bitmap.close()
                            crops.append((index, x0 * k, y0 * k, img))
                        page.close()
                finally:
                    pdf.close()
            per_page: dict[int, list] = {i: [] for i in sorted(regions)}
            scores: dict[int, list] = {i: [] for i in sorted(regions)}
            # crops are rendered at `dpi` already: no upscaling (20% faster, same text); several run side by side
            results = list(self._page_pool.map(lambda c: self.recognise_page(c[3], min_score, upscale=False), crops))
            for (index, ox, oy, img), r in zip(crops, results):
                back = img.shape[1] / r["width"]
                for b in r["boxes"]:
                    bx = b["box"]
                    per_page[index].append({"text": b["text"], "confidence": b["confidence"],
                                            "box": [round(ox + bx[0] * back), round(oy + bx[1] * back),
                                                    round(ox + bx[2] * back), round(oy + bx[3] * back)]})
                    scores[index].append(b["confidence"])
        pages = [{"page": i + 1, "boxes": per_page[i], "lines": [], "text": "",
                  "meanConfidence": round(statistics.fmean(scores[i]), 4) if scores[i] else 0.0}
                 for i in sorted(regions)]
        return {"engine": "rapidocr-ppocr", "pages": pages, "dpi": dpi, "regions": len(crops),
                "elapsedMs": round((time.perf_counter() - start) * 1000)}

    def recognise(self, data: bytes, dpi: int = 300, max_pages: int = 10, min_score: float = 0.5,
                  only: list[int] | None = None) -> dict:
        with self._admit:
            return self._recognise_document(data, dpi, max_pages, min_score, only)

    def _recognise_document(self, data: bytes, dpi: int, max_pages: int, min_score: float,
                            only: list[int] | None = None) -> dict:
        start = time.perf_counter()
        rendered = render_pages(data, dpi, max_pages, only)
        # Pages overlap: page 2 is detected while page 1's lines are being recognised.
        pages = list(self._page_pool.map(lambda p: self.recognise_page(p, min_score), rendered)) if len(rendered) > 1 \
            else [self.recognise_page(rendered[0], min_score)] if rendered else []
        confidences = [p["meanConfidence"] for p in pages if p["boxes"]]
        return {
            "engine": "rapidocr-ppocr",
            "text": "\n".join(p["text"] for p in pages) + "\n",
            "meanConfidence": round(statistics.fmean(confidences), 4) if confidences else 0.0,
            "pages": pages,
            "dpi": dpi,
            "elapsedMs": round((time.perf_counter() - start) * 1000),
        }
