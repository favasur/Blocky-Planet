package com.bowerbyte.blockyplanet.planet;

/**
 * Immutable 3D vector with double precision for quad sphere coordinate math.
 */
public record Vector3d(double x, double y, double z) {

    public static final Vector3d ZERO = new Vector3d(0, 0, 0);

    public Vector3d add(Vector3d other) {
        return new Vector3d(x + other.x, y + other.y, z + other.z);
    }

    public Vector3d subtract(Vector3d other) {
        return new Vector3d(x - other.x, y - other.y, z - other.z);
    }

    public Vector3d scale(double factor) {
        return new Vector3d(x * factor, y * factor, z * factor);
    }

    public double dot(Vector3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    /**
     * Normalize this vector to unit length. Returns ZERO if length is zero.
     */
    public Vector3d normalize() {
        double len = length();
        if (len < 1e-12) return ZERO;
        return scale(1.0 / len);
    }

    /**
     * Absolute value per component.
     */
    public Vector3d abs() {
        return new Vector3d(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    /**
     * Component-wise max absolute index: returns 0 for X, 1 for Y, 2 for Z.
     */
    public int maxAbsAxis() {
        Vector3d a = abs();
        if (a.x >= a.y && a.x >= a.z) return 0;
        if (a.y >= a.z) return 1;
        return 2;
    }

    /**
     * Sign of a component: +1.0, -1.0, or 0.0
     */
    public static double sign(double v) {
        if (v > 0) return 1.0;
        if (v < 0) return -1.0;
        return 0.0;
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }
}
