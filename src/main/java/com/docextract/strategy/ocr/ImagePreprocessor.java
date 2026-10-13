package com.docextract.strategy.ocr;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;

/**
 * Classic scan clean-up before OCR: grayscale, 3x3 median filter (removes salt-and-pepper speckle,
 * which Tesseract otherwise reads as stray characters), then Otsu's global threshold to pure black/white.
 */
public final class ImagePreprocessor {

    private ImagePreprocessor() {
    }

    /** Share of speck-sized blobs above which a page is a noisy scan. Measured: clean renders <= 0.011, photocopy speckle 0.95. */
    static final double NOISE_THRESHOLD = 0.25;

    /**
     * Speckle estimate: binarise, find connected dark blobs, and return the share that are speck-sized
     * (far smaller than any glyph at this resolution). Text yields mostly glyph-sized blobs (only dots and
     * periods are small), while photocopy speckle adds thousands of tiny ones.
     */
    public static double noiseLevel(BufferedImage src) {
        int[][] g = toGray(src);
        int h = g.length;
        int w = g[0].length;
        int threshold = otsuThreshold(g);
        // A speck covers at most ~0.15% of the page width squared: ~4 px at 1700 px wide, ~9 px at 2550.
        int maxSpeck = Math.max(2, (int) Math.round(Math.pow(w / 850.0, 2)));
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        long blobs = 0;
        long specks = 0;
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || g[start / w][start % w] > threshold) {
                continue;
            }
            int size = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int p = stack[--top];
                size++;
                int py = p / w;
                int px = p % w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int ny = py + dy;
                        int nx = px + dx;
                        if (ny >= 0 && ny < h && nx >= 0 && nx < w) {
                            int q = ny * w + nx;
                            if (!seen[q] && g[ny][nx] <= threshold) {
                                seen[q] = true;
                                stack[top++] = q;
                            }
                        }
                    }
                }
            }
            blobs++;
            if (size <= maxSpeck) {
                specks++;
            }
        }
        return blobs == 0 ? 0 : (double) specks / blobs;
    }

    public static boolean isNoisy(BufferedImage src) {
        return noiseLevel(src) > NOISE_THRESHOLD;
    }

    /**
     * Layout clean-up for designed documents (invoices, forms, statements), on the grayscale page:
     * <ol>
     *   <li>Solid dark regions (header bars, filled cells) are inverted, so white-on-dark captions become
     *       ordinary dark-on-light text that Tesseract reads.</li>
     *   <li>Long straight horizontal/vertical runs (table borders, cell grids, underlines) are erased; they make
     *       Tesseract drop the text inside ruled cells. No glyph has a straight stroke that long.</li>
     * </ol>
     */
    public static BufferedImage layoutClean(BufferedImage src) {
        int[][] g = toGray(src);
        int h = g.length;
        int w = g[0].length;
        int threshold = otsuThreshold(g);
        invertDarkRegions(g, threshold);
        removeRules(g, threshold, Math.max(40, w / 25), Math.max(40, h / 25));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster r = out.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                r.setSample(x, y, 0, g[y][x]);
            }
        }
        return out;
    }

    /** Dark blobs at least 1/8 of the page wide that are at least 70% solid (header bars, filled cells): invert the box. */
    static void invertDarkRegions(int[][] g, int threshold) {
        int h = g.length;
        int w = g[0].length;
        int minHeight = Math.max(12, h / 150);
        long minArea = (long) (w / 20) * minHeight;
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        List<int[]> boxes = new ArrayList<>();
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || g[start / w][start % w] > threshold) {
                continue;
            }
            int minX = w, minY = h, maxX = 0, maxY = 0;
            long size = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int p = stack[--top];
                int py = p / w;
                int px = p % w;
                size++;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
                minY = Math.min(minY, py);
                maxY = Math.max(maxY, py);
                // 4-connectivity keeps a bar separate from text that merely touches its corner.
                int[][] next = {{py - 1, px}, {py + 1, px}, {py, px - 1}, {py, px + 1}};
                for (int[] n : next) {
                    if (n[0] >= 0 && n[0] < h && n[1] >= 0 && n[1] < w) {
                        int q = n[0] * w + n[1];
                        if (!seen[q] && g[n[0]][n[1]] <= threshold) {
                            seen[q] = true;
                            stack[top++] = q;
                        }
                    }
                }
            }
            long boxArea = (long) (maxX - minX + 1) * (maxY - minY + 1);
            // A bar is wide and nearly solid (white captions inside take well under a third); bold words are neither.
            if (maxY - minY + 1 >= minHeight && maxX - minX + 1 >= w / 8 && boxArea >= minArea && size >= 0.7 * boxArea) {
                boxes.add(new int[]{minX, minY, maxX, maxY});
            }
        }
        for (int[] b : boxes) {
            for (int y = b[1]; y <= b[3]; y++) {
                for (int x = b[0]; x <= b[2]; x++) {
                    g[y][x] = 255 - g[y][x];
                }
            }
        }
    }

    /** Whitens horizontal runs of at least minRunX dark pixels and vertical runs of at least minRunY. */
    static void removeRules(int[][] g, int threshold, int minRunX, int minRunY) {
        int h = g.length;
        int w = g[0].length;
        boolean[][] erase = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            int run = 0;
            for (int x = 0; x <= w; x++) {
                if (x < w && g[y][x] <= threshold) {
                    run++;
                } else {
                    if (run >= minRunX) {
                        for (int k = x - run; k < x; k++) {
                            erase[y][k] = true;
                        }
                    }
                    run = 0;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = 0; y <= h; y++) {
                if (y < h && g[y][x] <= threshold) {
                    run++;
                } else {
                    if (run >= minRunY) {
                        for (int k = y - run; k < y; k++) {
                            erase[k][x] = true;
                        }
                    }
                    run = 0;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (erase[y][x]) {
                    g[y][x] = 255;
                }
            }
        }
    }

    public static BufferedImage clean(BufferedImage src) {
        int[][] gray = toGray(src);
        int[][] filtered = median3x3(gray);
        return binarise(filtered, otsuThreshold(filtered));
    }

    static int[][] toGray(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[][] g = new int[h][w];
        if (src.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            Raster r = src.getRaster();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    g[y][x] = r.getSample(x, y, 0);
                }
            }
            return g;
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                g[y][x] = (int) Math.round(0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff));
            }
        }
        return g;
    }

    static int[][] median3x3(int[][] g) {
        int h = g.length;
        int w = g[0].length;
        int[][] out = new int[h][w];
        int[] win = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.clamp(y + dy, 0, h - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        win[n++] = g[yy][Math.clamp(x + dx, 0, w - 1)];
                    }
                }
                java.util.Arrays.sort(win);
                out[y][x] = win[4];
            }
        }
        return out;
    }

    /** Otsu: the threshold that maximises between-class variance of the gray-level histogram. */
    static int otsuThreshold(int[][] g) {
        long[] hist = new long[256];
        long total = 0;
        for (int[] row : g) {
            for (int v : row) {
                hist[v]++;
                total++;
            }
        }
        double sumAll = 0;
        for (int i = 0; i < 256; i++) {
            sumAll += (double) i * hist[i];
        }
        double sumBack = 0;
        long weightBack = 0;
        double best = -1;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            weightBack += hist[t];
            if (weightBack == 0) {
                continue;
            }
            long weightFore = total - weightBack;
            if (weightFore == 0) {
                break;
            }
            sumBack += (double) t * hist[t];
            double meanBack = sumBack / weightBack;
            double meanFore = (sumAll - sumBack) / weightFore;
            double between = (double) weightBack * weightFore * (meanBack - meanFore) * (meanBack - meanFore);
            if (between > best) {
                best = between;
                threshold = t;
            }
        }
        return threshold;
    }

    static BufferedImage binarise(int[][] g, int threshold) {
        int h = g.length;
        int w = g[0].length;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster r = out.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                r.setSample(x, y, 0, g[y][x] > threshold ? 255 : 0);
            }
        }
        return out;
    }
}
