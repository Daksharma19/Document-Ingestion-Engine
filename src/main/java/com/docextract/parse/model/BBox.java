package com.docextract.parse.model;

/** A box on the page in PDF points, origin top-left (x grows right, y grows down). */
public record BBox(double x0, double y0, double x1, double y1) {

    public BBox union(BBox o) {
        return o == null ? this : new BBox(Math.min(x0, o.x0), Math.min(y0, o.y0), Math.max(x1, o.x1), Math.max(y1, o.y1));
    }
}
