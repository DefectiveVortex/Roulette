package com.vortex.roulette.model.layout;

/** An axis-aligned cell on the felt, in cell units. Left and top edges belong to it, right and bottom do not. */
public record FeltRect(double u0, double v0, double u1, double v1) {

    public boolean contains(double u, double v) {
        return u >= u0 && u < u1 && v >= v0 && v < v1;
    }

    public FeltPoint centre() {
        return new FeltPoint((u0 + u1) / 2, (v0 + v1) / 2);
    }

    public double width() {
        return u1 - u0;
    }

    public double height() {
        return v1 - v0;
    }
}
