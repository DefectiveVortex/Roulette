package com.vortex.roulette.display;

/**
 * Where the rotor and the ball are at every tick of one spin. Pure maths, no server.
 *
 * <p>The result is known before the spin starts, so nothing here is simulated. The ball is described
 * <em>relative to the rotor</em>, by a curve whose end point is the result pocket and whose speed there is zero.
 * Whatever the rotor does, and however many ticks the server skips, the ball is in the right pocket once
 * {@link #settleTick()} has passed. Variety comes from {@code variation}, which only changes how far the ball
 * travels before it gets there.
 *
 * <p>Angles are radians, clockwise seen from above, measured the same way as in docs/ART-CONTRACT.md: pocket
 * {@code i} of the rotor picture is centred at {@code i * 2π / pockets} from the picture's 12 o'clock. Radii are a
 * fraction of the wheel picture's width.
 */
public final class SpinCurve {

    /** The ball's centre while it circles on the track, and at rest in a pocket (docs/ART-CONTRACT.md: 220, 154). */
    public static final double TRACK_RADIUS = 220.0 / 512;
    public static final double REST_RADIUS = 154.0 / 512;

    /** Rotor speed while the ball is out, in turns per tick (0.3 turns per second). */
    static final double ROTOR_SPEED = 0.015;
    /** How fast the ball leaves the croupier's hand, relative to the rotor, in turns per tick. */
    static final double BALL_START_SPEED = 0.085;
    /** {@code variation} adds up to this much to the start speed: one more lap in a spin of the usual length. */
    static final double BALL_SPEED_SPREAD = 0.02;
    /** The ball's speed relative to the rotor falls off as (time left) to this power minus one. */
    static final double DECAY = 3;
    /** The ball leaves the track after this share of its travel time. */
    static final double DROP_AT = 0.72;
    static final int DROP_TICKS = 24;
    static final int SPIN_UP_TICKS = 20;
    static final int SPIN_DOWN_TICKS = 100;
    static final int REST_BEFORE_RESULT = 10;

    private final int pockets;
    private final double step;
    private final double restAngle;
    private final double rotorStart;
    private final double travel;
    private final int duration;
    private final int settleTick;
    private final int dropTick;
    private final int dropTicks;
    private final int spinUp;

    /**
     * @param pockets       37 or 38
     * @param resultIndex   the result's index in the wheel's clockwise pocket order
     * @param durationTicks ticks from the start of the spin to the payout
     * @param rotorStart    the rotor's angle when the spin starts
     * @param variation     0 to 1: how hard the ball was thrown, which changes how many laps it makes
     */
    public SpinCurve(int pockets, int resultIndex, int durationTicks, double rotorStart, double variation) {
        if (pockets < 1 || resultIndex < 0 || resultIndex >= pockets) {
            throw new IllegalArgumentException("pocket " + resultIndex + " of " + pockets);
        }
        this.pockets = pockets;
        this.duration = Math.max(2, durationTicks);
        this.step = Math.TAU / pockets;
        this.restAngle = resultIndex * step;
        this.rotorStart = rotorStart;
        this.settleTick = Math.max(1, duration - Math.min(REST_BEFORE_RESULT, duration / 8));
        this.dropTick = Math.min((int) Math.round(settleTick * DROP_AT), settleTick - 1);
        this.dropTicks = Math.min(DROP_TICKS, settleTick - dropTick);
        this.spinUp = Math.min(SPIN_UP_TICKS, duration / 4);
        double startSpeed = BALL_START_SPEED + BALL_SPEED_SPREAD * Math.clamp(variation, 0.0, 1.0);
        this.travel = Math.TAU * startSpeed * settleTick / DECAY;
    }

    /** From this tick on the ball lies in the result pocket and turns with the rotor. */
    public int settleTick() {
        return settleTick;
    }

    /** The tick at which the ball leaves the track and falls towards the pockets. */
    public int dropTick() {
        return dropTick;
    }

    /** The rotor is at a standstill from this tick on. */
    public int endTick() {
        return duration + SPIN_DOWN_TICKS;
    }

    /** The rotor picture's angle: it speeds up, turns steadily until the payout, then coasts to a stop. */
    public double rotorAngle(double tick) {
        double t = Math.max(0, tick);
        double turns;
        if (t < spinUp) {
            turns = t * t / (2 * spinUp);
        } else if (t <= duration) {
            turns = t - spinUp / 2.0;
        } else {
            double after = Math.min(t - duration, SPIN_DOWN_TICKS);
            turns = duration - spinUp / 2.0 + after - after * after / (2 * SPIN_DOWN_TICKS);
        }
        return rotorStart + Math.TAU * ROTOR_SPEED * turns;
    }

    /** The ball's angle on the rotor picture: it runs against the rotor and ends on the result pocket's centre. */
    public double ballAngleOnRotor(double tick) {
        double left = 1 - Math.clamp(tick / settleTick, 0.0, 1.0);
        return restAngle + travel * Math.pow(left, DECAY);
    }

    /** The ball's angle as the players see it. */
    public double ballAngle(double tick) {
        return rotorAngle(tick) + ballAngleOnRotor(tick);
    }

    /** The ball's distance from the centre: on the track, then a fall with a few bounces, then in the pocket. */
    public double ballRadius(double tick) {
        if (tick <= dropTick) {
            return TRACK_RADIUS;
        }
        double fallen = bounce(Math.min(1, (tick - dropTick) / dropTicks));
        return TRACK_RADIUS + (REST_RADIUS - TRACK_RADIUS) * fallen;
    }

    /** The index of the pocket the ball is over, for the click as it crosses a divider. */
    public int pocketUnderBall(double tick) {
        return Math.floorMod(Math.round(ballAngleOnRotor(tick) / step), pockets);
    }

    /** 0 to 1, arriving early and bouncing back three times, each time less. */
    private static double bounce(double u) {
        final double n = 7.5625;
        final double d = 2.75;
        if (u < 1 / d) {
            return n * u * u;
        } else if (u < 2 / d) {
            u -= 1.5 / d;
            return n * u * u + 0.75;
        } else if (u < 2.5 / d) {
            u -= 2.25 / d;
            return n * u * u + 0.9375;
        }
        u -= 2.625 / d;
        return n * u * u + 0.984375;
    }
}
