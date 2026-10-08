package com.vortex.roulette.model.layout;

import java.util.Optional;

/**
 * Where one table's felt lies in the world: the position of the grid's (0,0) corner on the felt surface, the
 * horizontal direction {@code u} runs in (one of the four block directions) and the size of a cell in blocks.
 * {@code v} is always {@code u} turned clockwise seen from above, so the layout reads the right way round.
 */
public record FeltFrame(double originX, double originY, double originZ, int uX, int uZ, double blocksPerUnit) {

    /** A point in the world. */
    public record Position(double x, double y, double z) {
    }

    public FeltFrame {
        if (Math.abs(uX) + Math.abs(uZ) != 1) {
            throw new IllegalArgumentException("u must be one of the four block directions: " + uX + "," + uZ);
        }
        if (!(blocksPerUnit > 0)) {
            throw new IllegalArgumentException("blocksPerUnit must be positive: " + blocksPerUnit);
        }
    }

    public int vX() {
        return -uZ;
    }

    public int vZ() {
        return uX;
    }

    public Position toWorld(double u, double v) {
        return toWorld(u, v, 0);
    }

    /** The point {@code lift} blocks above the felt at (u,v). */
    public Position toWorld(double u, double v, double lift) {
        double du = u * blocksPerUnit;
        double dv = v * blocksPerUnit;
        return new Position(originX + du * uX + dv * vX(), originY + lift, originZ + du * uZ + dv * vZ());
    }

    public Position toWorld(FeltPoint point) {
        return toWorld(point.u(), point.v(), 0);
    }

    /** The felt coordinates straight below or above a world position. */
    public FeltPoint toFelt(double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        return new FeltPoint((dx * uX + dz * uZ) / blocksPerUnit, (dx * vX() + dz * vZ()) / blocksPerUnit);
    }

    /**
     * Where a look ray meets the plane of the felt. Empty when the eye is not above the felt, the ray does not
     * point down at it, or the meeting point is further than {@code maxDistance} blocks along the ray. The
     * point may lie outside the grid: {@link FeltLayout#spotAt} decides that.
     */
    public Optional<FeltPoint> hit(double eyeX, double eyeY, double eyeZ,
                                   double dirX, double dirY, double dirZ, double maxDistance) {
        double height = eyeY - originY;
        if (!(height > 0) || !(dirY < 0)) {
            return Optional.empty();
        }
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        double t = height / -dirY;
        if (!(t * length <= maxDistance)) {
            return Optional.empty();
        }
        return Optional.of(toFelt(eyeX + dirX * t, eyeZ + dirZ * t));
    }
}
