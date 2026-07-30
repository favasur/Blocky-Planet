package com.bowerbyte.blockyplanet.planet;

/**
 * A 2D double-precision vector, used for (u, v) coordinates on cube faces.
 */
public record Vector2d(double u, double v) {

    public static final Vector2d ZERO = new Vector2d(0, 0);

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", u, v);
    }
}
