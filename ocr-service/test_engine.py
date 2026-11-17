"""Run: .venv/Scripts/python -m unittest discover -s ocr-service"""
import os
import unittest
from unittest import mock

from engine import OcrEngine


class DeviceSelectionTest(unittest.TestCase):
    def test_unknown_device_is_rejected(self):
        with self.assertRaises(ValueError):
            OcrEngine(device="tpu")

    def test_gpu_that_cannot_load_falls_back_to_cpu(self):
        build = OcrEngine._build

        def broken_gpu(self, device, threads=None):
            if device != "cpu":
                raise RuntimeError("simulated GPU provider failure")
            return build(self, device, threads)

        with mock.patch.dict(os.environ, {"OCR_DEVICE": "cuda"}), mock.patch.object(OcrEngine, "_build", broken_gpu):
            e = OcrEngine()
        self.assertEqual(e.device, "cpu")
        self.assertEqual((e.providers["det"], e.providers["rec"]), ("CPUExecutionProvider", "CPUExecutionProvider"))
        with open(os.path.join(os.path.dirname(__file__), "..", "corpus", "scanned-004.pdf"), "rb") as f:
            r = e.recognise(f.read())
        self.assertIn("Total Due: 281.05", r["text"])


class SplitPointsTest(unittest.TestCase):
    def test_long_lines_are_cut_only_at_word_gaps(self):
        import cv2
        import numpy as np
        from engine import split_points
        img = np.full((40, 1600, 3), 255, np.uint8)
        x = 10
        words = ["Invoice", "number", "INV-2024-001", "issued", "to", "Sharma", "Stores", "on", "12", "March"] * 2
        starts = []
        for w in words:
            starts.append(x)
            cv2.putText(img, w, (x, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 2)
            x += cv2.getTextSize(w, cv2.FONT_HERSHEY_SIMPLEX, 0.8, 2)[0][0] + 22  # a word space
        cuts = split_points(img[:, :x], 12)
        self.assertTrue(cuts, "a 40:1 line is split")
        ink = (img[:, :x] < 128).any(axis=(0, 2))
        for c in cuts:
            self.assertFalse(ink[c], f"cut at x={c} goes through a glyph")
        self.assertEqual(split_points(img[:, :300], 12), [], "short lines are left whole")


if __name__ == "__main__":
    unittest.main()
