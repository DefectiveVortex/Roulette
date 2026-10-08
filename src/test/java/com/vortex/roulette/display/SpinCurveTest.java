package com.vortex.roulette.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortex.roulette.model.WheelType;
import org.junit.jupiter.api.Test;

class SpinCurveTest {

    private static final int[] DURATIONS = {2, 5, 20, 60, 160, 400};
    private static final double[] VARIATIONS = {0, 0.37, 1};

    /** Distance between two angles, 0 to π. */
    private static double apart(double a, double b) {
        double d = Math.abs(a - b) % Math.TAU;
        return Math.min(d, Math.TAU - d);
    }

    @Test
    void theBallRestsOnTheResultPocketForEveryPocketOfBothWheels() {
        for (WheelType wheel : WheelType.values()) {
            double step = Math.TAU / wheel.size();
            for (int result = 0; result < wheel.size(); result++) {
                for (int duration : DURATIONS) {
                    for (double variation : VARIATIONS) {
                        SpinCurve curve = new SpinCurve(wheel.size(), result, duration, 1.234, variation);
                        assertTrue(curve.settleTick() <= duration, "settled before the payout");
                        for (int tick = curve.settleTick(); tick <= curve.endTick() + 40; tick += 3) {
                            String at = wheel + " pocket " + result + " duration " + duration + " tick " + tick;
                            assertEquals(0, apart(curve.ballAngle(tick), curve.rotorAngle(tick) + result * step),
                                    1e-9, at);
                            assertEquals(SpinCurve.REST_RADIUS, curve.ballRadius(tick), 1e-12, at);
                            assertEquals(result, curve.pocketUnderBall(tick), at);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theBallStartsOnTheTrackAndOnlyEverMovesInwardOverall() {
        SpinCurve curve = new SpinCurve(37, 5, 160, 0, 0.5);
        assertEquals(SpinCurve.TRACK_RADIUS, curve.ballRadius(0), 0);
        assertEquals(SpinCurve.TRACK_RADIUS, curve.ballRadius(curve.dropTick()), 0);
        assertTrue(curve.dropTick() < curve.settleTick());
        for (double tick = 0; tick <= 200; tick += 0.25) {
            double radius = curve.ballRadius(tick);
            assertTrue(radius <= SpinCurve.TRACK_RADIUS + 1e-12 && radius >= SpinCurve.REST_RADIUS - 1e-12,
                    "radius " + radius + " at tick " + tick);
        }
    }

    @Test
    void nothingJumps() {
        // A display entity turns the short way between two updates, so a step must stay well under half a turn,
        // and the ball must never appear to teleport.
        for (int duration : DURATIONS) {
            for (double variation : VARIATIONS) {
                SpinCurve curve = new SpinCurve(38, 19, duration, 0.5, variation);
                for (int tick = 0; tick < curve.endTick() + 20; tick++) {
                    double ball = Math.abs(curve.ballAngle(tick + 1) - curve.ballAngle(tick));
                    double rotor = curve.rotorAngle(tick + 1) - curve.rotorAngle(tick);
                    double radius = Math.abs(curve.ballRadius(tick + 1) - curve.ballRadius(tick));
                    assertTrue(ball < Math.PI / 3, "ball turns " + ball + " in tick " + tick + " of " + duration);
                    assertTrue(rotor >= 0 && rotor < 0.1, "rotor turns " + rotor + " in tick " + tick);
                    if (duration >= 60) {
                        assertTrue(radius < 0.05, "ball falls " + radius + " in tick " + tick + " of " + duration);
                    }
                }
            }
        }
    }

    @Test
    void theBallRunsAgainstTheRotorAndSlowsDownAllTheWay() {
        SpinCurve curve = new SpinCurve(37, 12, 160, 0, 0.2);
        double previousStep = Double.MAX_VALUE;
        for (int tick = 0; tick < curve.settleTick(); tick++) {
            double moved = curve.ballAngleOnRotor(tick) - curve.ballAngleOnRotor(tick + 1);
            assertTrue(moved > 0, "moves against the rotor in tick " + tick);
            assertTrue(moved < previousStep, "slower than the tick before, tick " + tick);
            previousStep = moved;
        }
    }

    @Test
    void theRotorStartsWhereItWasAndStops() {
        SpinCurve curve = new SpinCurve(37, 0, 160, 2.5, 0);
        assertEquals(2.5, curve.rotorAngle(0), 0);
        assertEquals(2.5, curve.rotorAngle(-3), 0);
        assertEquals(curve.rotorAngle(curve.endTick()), curve.rotorAngle(curve.endTick() + 500), 0);
        assertEquals(0, curve.rotorAngle(curve.endTick() + 1) - curve.rotorAngle(curve.endTick()), 1e-12);
    }

    @Test
    void aSkippedTickChangesNothing() {
        // The position depends on the tick asked for and on nothing before it.
        SpinCurve a = new SpinCurve(38, 7, 160, 0.3, 0.6);
        SpinCurve b = new SpinCurve(38, 7, 160, 0.3, 0.6);
        for (int tick = 0; tick < 160; tick++) {
            a.ballAngle(tick);
        }
        assertEquals(a.ballAngle(160), b.ballAngle(160), 0);
        assertEquals(a.ballRadius(131.5), b.ballRadius(131.5), 0);
    }
}
