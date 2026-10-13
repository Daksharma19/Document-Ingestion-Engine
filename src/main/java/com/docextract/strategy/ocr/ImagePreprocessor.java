package com.docextract.strategy.ocr;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Classic scan clean-up before OCR: grayscale, 3x3 median filter (removes salt-and-pepper speckle,
 * which Tesseract otherwise reads as stray characters), then Otsu's global threshold to pure black/white.
 */
public final class ImagePreprocessor {

    private ImagePreprocessor() {
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
