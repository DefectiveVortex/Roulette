package com.vortex.roulette.model.layout;

/**
 * A position on the felt in cell units: {@code u} runs from the zero end to the column bets,
 * {@code v} from the 3-6-9 row down to the even-money bets. One unit is one number cell.
 */
public record FeltPoint(double u, double v) {

    public double distanceSquared(double otherU, double otherV) {
        double du = u - otherU;
        double dv = v - otherV;
        return du * du + dv * dv;
    }
}
