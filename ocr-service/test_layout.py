"""Run: .venv/Scripts/python -m unittest discover -s ocr-service"""
import unittest

from layout import Box, group_lines, to_lines, to_text


def box(text, x0, y0, x1, y1):
    return Box(text, 0.99, x0, y0, x1, y1)


class LayoutTest(unittest.TestCase):
    def test_wide_gaps_become_column_breaks_and_lines_keep_reading_order(self):
        boxes = [
            box("Total:", 100, 300, 160, 320),
            box("$5,249.15", 400, 302, 490, 322),
            box("Invoice No: 50056", 100, 100, 270, 120),
            box("Date", 100, 200, 140, 220),
            box("Sep 26 2012", 150, 201, 260, 221),  # a normal word gap, not a column
        ]
        self.assertEqual(to_text(boxes), "Invoice No: 50056\n\nDate Sep 26 2012\n\nTotal:   $5,249.15")

    def test_boxes_on_slightly_different_baselines_share_a_line(self):
        lines = group_lines([box("Qty", 10, 50, 40, 70), box("Amount", 300, 54, 370, 74), box("Next", 10, 90, 50, 110)])
        self.assertEqual([[b.text for b in line] for line in lines], [["Qty", "Amount"], ["Next"]])

    def test_lines_carry_box_height_and_confidence(self):
        lines = to_lines([Box("Total:", 0.9, 100, 300, 160, 320), Box("$5", 0.5, 400, 300, 420, 320)])
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["text"], "Total:   $5")
        self.assertEqual(lines[0]["box"], [100, 300, 420, 320])
        self.assertEqual(lines[0]["height"], 20)
        self.assertAlmostEqual(lines[0]["confidence"], (0.9 * 6 + 0.5 * 2) / 8, places=4)


if __name__ == "__main__":
    unittest.main()
