"""Builds a Latin-script copy of the PP-OCRv6 recognition model: same trained network, smaller output layer.

The bundled model scores every position of every text line against 18,710 characters (Chinese, Japanese, Korean,
emoji...). Business documents in English/European languages need a few hundred. Slicing the final linear layer to
those columns (plus the CTC blank and the space) leaves the backbone untouched, so Latin text is read exactly as
before, while the per-position work and the softmax shrink ~40x. It also cannot output CJK look-alikes.

Run directly to (re)build:  .venv/Scripts/python trim_model.py      (engine.py does this on first start)
"""
from __future__ import annotations

import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
LATIN_MODEL = HERE / "models" / "PP-OCRv6_rec_small_latin.onnx"
LATIN_TINY_MODEL = HERE / "models" / "PP-OCRv6_rec_tiny_latin.onnx"


def latin_charset() -> set[str]:
    ranges = [
        (0x20, 0x7E),      # ASCII
        (0xA0, 0x17F),     # Latin-1 supplement + Latin Extended-A (accents, £ ¥ © ® ° ± × ÷ ½ ...)
        (0x2010, 0x203A),  # dashes, quotes, bullets, ellipsis, per-mille
        (0x20A0, 0x20C0),  # currency signs (₹ € ₩ ₽ ...)
        (0x2100, 0x215F),  # letterlike (№ ™ ℅) and number forms (⅓ ...)
        (0x2190, 0x2199),  # arrows
        (0x2212, 0x2212),  # minus sign
        (0x25A0, 0x25CF),  # squares, circles (form check boxes, bullets)
        (0x2610, 0x2612),  # ballot boxes ☐ ☑ ☒
        (0x2713, 0x2718),  # check / cross marks
    ]
    return {chr(c) for a, b in ranges for c in range(a, b + 1)}


def build(source: Path, target: Path = LATIN_MODEL) -> Path:
    import numpy as np
    import onnx
    from onnx import numpy_helper

    model = onnx.load(str(source))
    meta = {p.key: p for p in model.metadata_props}
    chars = meta["character"].value.split("\n")
    n_out = len(chars) + 2  # index 0 = CTC blank, 1..len = metadata characters, last = space
    keep_chars = latin_charset()
    kept = [i for i, ch in enumerate(chars) if ch in keep_chars]
    columns = np.array([0] + [i + 1 for i in kept] + [n_out - 1], dtype=np.int64)

    sliced = 0
    for init in model.graph.initializer:
        if init.dims and init.dims[-1] == n_out:
            arr = numpy_helper.to_array(init)
            init.CopyFrom(numpy_helper.from_array(np.ascontiguousarray(arr[..., columns]), init.name))
            sliced += 1
    if sliced != 2:
        raise RuntimeError(f"expected the output layer's weight and bias ({n_out} wide), sliced {sliced} tensors")
    meta["character"].value = "\n".join(chars[i] for i in kept)
    # Output shapes were recorded with the old class count; let ONNX Runtime infer them again.
    for vi in list(model.graph.value_info):
        if any(d.dim_value == n_out for d in vi.type.tensor_type.shape.dim):
            model.graph.value_info.remove(vi)
    for out in model.graph.output:
        for d in out.type.tensor_type.shape.dim:
            if d.dim_value == n_out:
                d.dim_value = len(columns)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    onnx.save(model, str(tmp))
    os.replace(tmp, target)
    return target


def bundled_model(size: str = "small") -> Path:
    """RapidOCR ships the small recogniser; the tiny one is fetched by RapidOCR on first use."""
    import rapidocr
    path = Path(rapidocr.__file__).parent / "models" / f"PP-OCRv6_rec_{size}.onnx"
    if not path.exists():
        from rapidocr import ModelType, OCRVersion, RapidOCR
        RapidOCR(params={"Rec.ocr_version": OCRVersion.PPOCRV6, "Rec.model_type": ModelType(size),
                         "Global.log_level": "warning"})._load_rec_model()
    return path


def ensure_latin_model(size: str = "small") -> Path:
    target = LATIN_MODEL if size == "small" else LATIN_TINY_MODEL
    if not target.exists():
        build(bundled_model(size), target)
    return target


if __name__ == "__main__":
    for size in ("small", "tiny"):
        print("wrote", ensure_latin_model(size))
