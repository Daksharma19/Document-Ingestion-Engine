"""Turns OCR boxes back into reading-order text that keeps the page's column structure.

The Java mapper expects the same shape the PDF text layer produces: one line per visual line, and a wide
horizontal gap between two cells written as three spaces (a column break). See LayoutNormalizer.java.
"""
from __future__ import annotations

from dataclasses import dataclass
import statistics

COLUMN_GAP = "   "


@dataclass
class Box:
    text: str
    score: float
    x0: float
    y0: float
    x1: float
    y1: float

    @property
    def height(self) -> float:
        return self.y1 - self.y0

    @property
    def cy(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def char_width(self) -> float:
        n = max(1, len(self.text))
        return (self.x1 - self.x0) / n

    @classmethod
    def from_quad(cls, quad, text: str, score: float) -> "Box":
        xs = [p[0] for p in quad]
        ys = [p[1] for p in quad]
        return cls(text, float(score), min(xs), min(ys), max(xs), max(ys))


def group_lines(boxes: list[Box]) -> list[list[Box]]:
    """Groups boxes into visual lines: a box joins a line when it overlaps the line's vertical band by half
    of the smaller height. Boxes are visited top to bottom, so tall boxes do not swallow neighbouring lines."""
    lines: list[list[Box]] = []
    for b in sorted(boxes, key=lambda b: (b.cy, b.x0)):
        best, best_overlap = None, 0.0
        for line in lines[-4:]:  # only recent lines can still overlap
            top = statistics.median(x.y0 for x in line)
            bottom = statistics.median(x.y1 for x in line)
            overlap = min(bottom, b.y1) - max(top, b.y0)
            need = 0.5 * min(bottom - top, b.height)
            if overlap >= need and overlap > best_overlap:
                best, best_overlap = line, overlap
        if best is None:
            lines.append([b])
        else:
            best.append(b)
    for line in lines:
        line.sort(key=lambda b: b.x0)
    lines.sort(key=lambda line: statistics.median(b.cy for b in line))
    return lines


def render_line(line: list[Box], gap_factor: float = 2.0) -> str:
    out = line[0].text
    for prev, cur in zip(line, line[1:]):
        cw = max(1.0, min(prev.char_width, cur.char_width))
        gap = cur.x0 - prev.x1
        out += (COLUMN_GAP if gap > gap_factor * cw else " ") + cur.text
    return out


def to_text(boxes: list[Box], page_height: float | None = None) -> str:
    """Reading-order text. A vertical gap of more than ~1.5 line heights becomes a blank line, so paragraph
    and section breaks survive (the section extractor relies on them)."""
    lines = group_lines(boxes)
    if not lines:
        return ""
    heights = [b.height for b in boxes if b.height > 0]
    typical = statistics.median(heights) if heights else 10.0
    rendered: list[str] = []
    prev_bottom = None
    for line in lines:
        top = min(b.y0 for b in line)
        if prev_bottom is not None and top - prev_bottom > 1.5 * typical:
            rendered.append("")
        rendered.append(render_line(line))
        prev_bottom = max(b.y1 for b in line)
    return "\n".join(rendered)


def to_lines(boxes: list[Box]) -> list[dict]:
    """Visual lines with geometry, for callers that build their own structure (headings, tables, paragraphs).
    `text` uses the same column-gap convention as to_text; `confidence` is the character-weighted mean score;
    `height` is the median box height (a proxy for font size)."""
    out = []
    for line in group_lines(boxes):
        chars = sum(max(1, len(b.text)) for b in line)
        out.append({
            "text": render_line(line),
            "box": [round(min(b.x0 for b in line)), round(min(b.y0 for b in line)),
                    round(max(b.x1 for b in line)), round(max(b.y1 for b in line))],
            "height": round(statistics.median(b.height for b in line), 1),
            "confidence": round(sum(b.score * max(1, len(b.text)) for b in line) / chars, 4),
        })
    return out
