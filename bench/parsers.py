"""One adapter per parser. Each takes a file path and returns plain text.

Adapters whose library isn't installed are skipped (see available()).
Add a new one by writing a function and registering it in PARSERS.
"""
from __future__ import annotations

import io
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PDF = {".pdf"}
IMG = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}


class Unsupported(Exception):
    """The parser can't handle this file type (not counted as a failure)."""


def _pdf_images(path: Path, dpi: int = 300):
    import pypdfium2 as pdfium
    doc = pdfium.PdfDocument(str(path))
    try:
        return [page.render(scale=dpi / 72).to_pil() for page in doc]
    finally:
        doc.close()


def _images(path: Path):
    from PIL import Image
    ext = path.suffix.lower()
    if ext in PDF:
        return _pdf_images(path)
    if ext in IMG:
        return [Image.open(path).convert("RGB")]
    raise Unsupported(ext)


# ---------- text-layer extractors (no OCR) ----------

def pypdf_parse(path: Path) -> str:
    if path.suffix.lower() not in PDF:
        raise Unsupported(path.suffix)
    from pypdf import PdfReader
    return "\n".join(p.extract_text() or "" for p in PdfReader(str(path)).pages)


def pdfplumber_parse(path: Path) -> str:
    if path.suffix.lower() not in PDF:
        raise Unsupported(path.suffix)
    import pdfplumber
    with pdfplumber.open(str(path)) as pdf:
        return "\n".join(p.extract_text() or "" for p in pdf.pages)


def pymupdf_parse(path: Path) -> str:
    if path.suffix.lower() not in PDF:
        raise Unsupported(path.suffix)
    import pymupdf as fitz
    with fitz.open(str(path)) as doc:
        return "\n".join(p.get_text() for p in doc)


# ---------- OCR ----------

LOCAL_TESS = ROOT / ".tools" / "tesseract"  # unpacked Ubuntu .debs, no sudo needed


def tesseract_bin() -> str | None:
    import shutil
    if shutil.which("tesseract"):
        return "tesseract"
    exe = LOCAL_TESS / "usr/bin/tesseract"
    if exe.exists():
        lib = str(LOCAL_TESS / "usr/lib/x86_64-linux-gnu")
        os.environ["LD_LIBRARY_PATH"] = lib + os.pathsep + os.environ.get("LD_LIBRARY_PATH", "")
        os.environ.setdefault("TESSDATA_PREFIX", str(next(LOCAL_TESS.glob("usr/share/tesseract-ocr/*/tessdata"))))
        return str(exe)
    return None


def tesseract_parse(path: Path) -> str:
    import pytesseract
    pytesseract.pytesseract.tesseract_cmd = tesseract_bin()
    return "\n".join(pytesseract.image_to_string(im) for im in _images(path))


_rapid = None


def rapidocr_parse(path: Path) -> str:
    """Your Python OCR service engine (RapidOCR / PP-OCR + preprocessing), in-process."""
    global _rapid
    if path.suffix.lower() not in PDF | IMG:
        raise Unsupported(path.suffix)
    if _rapid is None:
        sys.path.insert(0, str(ROOT / "ocr-service"))
        from engine import OcrEngine
        _rapid = OcrEngine()
    return _rapid.recognise(path.read_bytes())["text"]


# ---------- layout-aware converters ----------

_docling = None


def docling_parse(path: Path) -> str:
    global _docling
    if _docling is None:
        # Torch's CUDA build has no kernels for older GPUs (e.g. MX250), so run on CPU unless DOCLING_DEVICE says otherwise.
        from docling.datamodel.accelerator_options import AcceleratorOptions
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions
        from docling.document_converter import DocumentConverter, ImageFormatOption, PdfFormatOption
        opts = PdfPipelineOptions(accelerator_options=AcceleratorOptions(device=os.environ.get("DOCLING_DEVICE", "cpu")))
        _docling = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts),
                                                     InputFormat.IMAGE: ImageFormatOption(pipeline_options=opts)})
    return _docling.convert(str(path)).document.export_to_markdown()


def markitdown_parse(path: Path) -> str:
    from markitdown import MarkItDown
    return MarkItDown().convert(str(path)).text_content


# ---------- docextract (Spring /api/parse, full engine) ----------

DOCEXTRACT_URL = os.environ.get("DOCEXTRACT_URL", "http://127.0.0.1:8080")


def docextract_parse(path: Path) -> str:
    import requests
    with path.open("rb") as f:
        r = requests.post(f"{DOCEXTRACT_URL}/api/parse", files={"files": (path.name, f)},
                          data={"includeMarkdown": "true"}, timeout=300)
    r.raise_for_status()
    doc = r.json()[0]
    if doc.get("status") == "ERROR":
        raise RuntimeError(doc.get("error"))
    return doc.get("markdown") or ""


def _docextract_up() -> bool:
    import socket
    from urllib.parse import urlparse
    u = urlparse(DOCEXTRACT_URL)
    try:
        socket.create_connection((u.hostname, u.port or 80), timeout=0.5).close()
        return True
    except OSError:
        return False


# name -> (function, module needed to be importable or a check callable)
PARSERS = {
    "pypdf": (pypdf_parse, "pypdf"),
    "pdfplumber": (pdfplumber_parse, "pdfplumber"),
    "pymupdf": (pymupdf_parse, "pymupdf"),
    "tesseract": (tesseract_parse, "pytesseract"),
    "docling": (docling_parse, "docling"),
    "markitdown": (markitdown_parse, "markitdown"),
    "rapidocr-engine": (rapidocr_parse, "rapidocr"),
    "docextract": (docextract_parse, _docextract_up),
}


def available() -> dict:
    import importlib.util
    out = {}
    for name, (fn, need) in PARSERS.items():
        ok = need() if callable(need) else importlib.util.find_spec(need) is not None
        if name == "tesseract" and not tesseract_bin():
            ok = False
        if ok:
            out[name] = fn
    return out
