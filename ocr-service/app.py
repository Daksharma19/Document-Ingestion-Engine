"""HTTP front for the OCR engine. The Java API (docextract) owns routing, mapping and validation; this service
only turns page images into layout-preserving text.

Run:  .venv/Scripts/python -m uvicorn app:app --port 8001     (or ./run.ps1 / ./run.sh)
"""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from engine import OcrEngine

engine: OcrEngine | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global engine
    engine = OcrEngine()  # loads the ONNX models once (~2 s)
    yield


app = FastAPI(title="docextract-ocr", version="1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    if engine is None:
        return {"status": "STARTING", "engine": "rapidocr-ppocr"}
    return {"status": "UP", "engine": "rapidocr-ppocr", "device": engine.device, "providers": engine.providers}


@app.post("/ocr")
async def ocr(file: UploadFile = File(...),
              dpi: int = Form(300),
              max_pages: int = Form(10),
              min_score: float = Form(0.5),
              include_boxes: bool = Form(False),
              pages: str = Form(""),
              regions: str = Form("")) -> dict:
    """`pages`: optional comma-separated 1-based page numbers of a PDF to OCR (default: the first max_pages)."""
    if engine is None:
        raise HTTPException(503, "models still loading")
    if not 72 <= dpi <= 600 or not 1 <= max_pages <= 50:
        raise HTTPException(400, "dpi must be 72-600 and max_pages 1-50")
    try:
        only = [int(p) - 1 for p in pages.split(",") if p.strip()] or None
    except ValueError as e:
        raise HTTPException(400, "pages must be comma-separated page numbers") from e
    data = await file.read()
    if not data:
        raise HTTPException(400, "empty file")
    if regions:
        # {"3": [[x0, y0, x1, y1], ...]}: 1-based page -> rectangles in PDF points (top-left origin)
        import json
        try:
            wanted = {int(p) - 1: [[float(v) for v in r] for r in rs] for p, rs in json.loads(regions).items()}
        except (ValueError, TypeError, AttributeError) as e:
            raise HTTPException(400, "regions must be {page: [[x0,y0,x1,y1], ...]}") from e
        try:
            return await run_in_threadpool(engine.recognise_regions, data, dpi, wanted, min_score)
        except ValueError as e:
            raise HTTPException(415, str(e)) from e
    try:
        result = await run_in_threadpool(engine.recognise, data, dpi, max_pages, min_score, only)
    except ValueError as e:
        raise HTTPException(415, str(e)) from e
    if not include_boxes:
        for p in result["pages"]:
            p.pop("boxes", None)
    return result
