package com.favasur.blockyplanet.config;

/**
 * Configures the diameter of the Blocky Planet.
 *
 * The value is set via the planet-size screen on "Create New World"
 * and read by the chunk generator at worldgen time.
 *
 * Range: 500 blocks (250-block radius) up to 129_000_000 blocks
 *        (= 129 000 km diameter, roughly 10× Earth).
 */
public final class BlockyPlanetConfig {

    /** Smallest playable planet diameter (blocks). ~500 m — a large asteroid. */
    public static final int MIN_DIAMETER = 500;

    /** Largest planet diameter (blocks) = 129 000 km. */
    public static final int MAX_DIAMETER = 129_000_000;

    /** Default planet diameter (blocks) = Earth diameter = 12,742 km. */
    public static final int DEFAULT_DIAMETER = 12_742_000;

    /** Conversions. */
    public static final double BLOCKS_PER_KM = 1000.0;
    public static final double BLOCKS_PER_M = 1.0;

    // ─── Current value (static, set by GUI) ──────────────────────────────

    private static int planetDiameter = DEFAULT_DIAMETER;

    public static int getPlanetDiameter() {
        return planetDiameter;
    }

    /** Radius in blocks (half the diameter). */
    public static double getPlanetRadius() {
        return planetDiameter / 2.0;
    }

    public static void setPlanetDiameter(int diameter) {
        planetDiameter = Math.max(MIN_DIAMETER, Math.min(MAX_DIAMETER, diameter));
    }

    // ─── Logarithmic slider mapping ──────────────────────────────────────

    /**
     * Convert a slider position [0..1] to a planet diameter [MIN..MAX]
     * using a logarithmic scale.
     */
    public static int sliderToDiameter(double sliderPos) {
        sliderPos = Math.max(0.0, Math.min(1.0, sliderPos));
        double lnMin = Math.log(MIN_DIAMETER);
        double lnMax = Math.log(MAX_DIAMETER);
        double lnVal = lnMin + sliderPos * (lnMax - lnMin);
        return (int) Math.round(Math.exp(lnVal));
    }

    /**
     * Convert a planet diameter back to a slider position [0..1].
     */
    public static double diameterToSlider(int diameter) {
        diameter = Math.max(MIN_DIAMETER, Math.min(MAX_DIAMETER, diameter));
        double lnMin = Math.log(MIN_DIAMETER);
        double lnMax = Math.log(MAX_DIAMETER);
        double lnVal = Math.log(diameter);
        return (lnVal - lnMin) / (lnMax - lnMin);
    }

    // ─── Formatting ──────────────────────────────────────────────────────

    /**
     * Format a diameter to a human-readable string.
     * < 1 km → "X blocks"
     * ≥ 1 km → "X,XXX km"
     */
    public static String formatDiameter(int diameter) {
        if (diameter < 1_000) {
            return String.format("%,d blocks", diameter);
        }
        double km = diameter / BLOCKS_PER_KM;
        if (km < 10) {
            return String.format("%,.1f km", km);
        }
        return String.format("%,.0f km", km);
    }

    /**
     * Format the radius.
     */
    public static String formatRadius(double radius) {
        if (radius < 1_000) {
            return String.format("%,.0f blocks", radius);
        }
        double km = radius / BLOCKS_PER_KM;
        return String.format("%,.1f km", km);
    }

    // ─── Nether Ring (underground megabiome) ────────────────────────────

    /**
     * "Golden standard": Earth radius = 6,371,000 blocks, Nether is ~12,000
     * blocks below the surface.  The ratio is kept proportional for all
     * planet sizes.
     */
    public static final double NETHER_DEPTH_RATIO = 12000.0 / 6_371_000.0;  // ≈ 0.001884

    /**
     * Nether ring half-thickness as a fraction of planet radius.
     * For Earth this gives ~256 blocks half-thickness (512 total).
     */
    public static final double NETHER_HALF_THICKNESS_RATIO = 256.0 / 6_371_000.0; // ≈ 0.00004

    /** Minimum half-thickness in blocks (for tiny planets). */
    public static final double NETHER_MIN_HALF_THICKNESS = 8.0;

    /** Minimum centre depth in blocks (for very small planets). */
    public static final double NETHER_MIN_DEPTH = 16.0;

    /**
     * Depth from the surface to the centre of the Nether ring (blocks).
     * Enforced minimum so the ring never overlaps the surface on small planets.
     */
    public static double getNetherCenterDepth(double planetRadius) {
        return Math.max(NETHER_MIN_DEPTH, planetRadius * NETHER_DEPTH_RATIO);
    }

    /**
     * Inner boundary of the Nether ring (closer to planet centre).
     */
    public static double getNetherInnerRadius(double planetRadius) {
        double half = Math.max(NETHER_MIN_HALF_THICKNESS, planetRadius * NETHER_HALF_THICKNESS_RATIO);
        double depth = getNetherCenterDepth(planetRadius);
        return planetRadius - depth - half;
    }

    /**
     * Outer boundary of the Nether ring (closer to surface).
     */
    public static double getNetherOuterRadius(double planetRadius) {
        double half = Math.max(NETHER_MIN_HALF_THICKNESS, planetRadius * NETHER_HALF_THICKNESS_RATIO);
        double depth = getNetherCenterDepth(planetRadius);
        return planetRadius - depth + half;
    }

    /**
     * Depth from surface to the inner edge of the Nether ring.
     */
    public static double getNetherInnerDepth(double planetRadius) {
        return planetRadius - getNetherInnerRadius(planetRadius);
    }

    /**
     * Depth from surface to the outer edge of the Nether ring.
     */
    public static double getNetherOuterDepth(double planetRadius) {
        return planetRadius - getNetherOuterRadius(planetRadius);
    }

    /**
     * Whether a given distance-from-centre falls inside the Nether ring.
     */
    public static boolean isInNetherRing(double distFromCenter) {
        double r = getPlanetRadius();
        double inner = getNetherInnerRadius(r);
        double outer = getNetherOuterRadius(r);
        return distFromCenter >= inner && distFromCenter <= outer;
    }

    // ─── Horizon curvature ───────────────────────────────────────────────

    /**
     * Calculate the visible horizon dip angle (in degrees) for a given
     * planet radius and observer eye height.
     *
     * dip ≈ sqrt(2h / R) radians, where h is eye height above surface
     * and R is planet radius.
     *
     * @param radius   Planet radius in blocks
     * @param eyeHeight Eye height above the surface in blocks (~1.62 for a player)
     * @return Horizon dip angle in degrees (0 = perfectly flat)
     */
    public static double horizonDipDegrees(double radius, double eyeHeight) {
        if (radius <= 0 || eyeHeight <= 0) return 0;
        return Math.toDegrees(Math.sqrt(2.0 * eyeHeight / radius));
    }

    /**
     * Calculate the distance to the horizon (in blocks).
     * d ≈ sqrt(2Rh) where R is radius and h is eye height.
     */
    public static double horizonDistance(double radius, double eyeHeight) {
        if (radius <= 0 || eyeHeight <= 0) return 0;
        return Math.sqrt(2.0 * radius * eyeHeight + eyeHeight * eyeHeight);
    }

    /**
     * Get the current horizon dip angle.
     */
    public static double currentHorizonDip() {
        return horizonDipDegrees(getPlanetRadius(), 1.62);
    }

    /**
     * Get the current horizon distance.
     */
    public static double currentHorizonDistance() {
        return horizonDistance(getPlanetRadius(), 1.62);
    }
}
