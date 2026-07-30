package com.bowerbyte.blockyplanet.planet;

/**
 * The six (6) sectors of the quad sphere, one for each face of the enclosing cube.
 *
 * Each sector has a local 2D coordinate system (u, v) that maps to a
 * point on the cube face, which is then normalized onto the unit sphere.
 */
public enum SectorType {

    /** +X face */
    RIGHT(0,  1, 0, 0),
    /** -X face */
    LEFT(1,  -1, 0, 0),
    /** +Y face (top) */
    UP(2,    0, 1, 0),
    /** -Y face (bottom) */
    DOWN(3,  0, -1, 0),
    /** +Z face (front) */
    FRONT(4, 0, 0, 1),
    /** -Z face (back) */
    BACK(5,  0, 0, -1);

    private final int index;
    private final double nx;
    private final double ny;
    private final double nz;

    SectorType(int index, double nx, double ny, double nz) {
        this.index = index;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    /** Numeric index 0-5 used in BlockAddress. */
    public int index() {
        return index;
    }

    /** The axis-aligned normal of this cube face (before spherical projection). */
    public Vector3d normal() {
        return new Vector3d(nx, ny, nz);
    }

    /**
     * Given a position relative to planet center, determine which sector it
     * falls in. The sector is the cube face whose axis has the maximum
     * absolute coordinate value.
     */
    public static SectorType fromPosition(Vector3d pos) {
        int axis = pos.maxAbsAxis();
        double comp = switch (axis) {
            case 0 -> pos.x();
            case 1 -> pos.y();
            case 2 -> pos.z();
            default -> 0;
        };
        boolean positive = comp >= 0;
        return switch (axis) {
            case 0 -> positive ? RIGHT : LEFT;
            case 1 -> positive ? UP : DOWN;
            case 2 -> positive ? FRONT : BACK;
            default -> FRONT;
        };
    }

    /**
     * Get sector by its numeric index (0-5).
     */
    public static SectorType fromIndex(int index) {
        return switch (index) {
            case 0 -> RIGHT;
            case 1 -> LEFT;
            case 2 -> UP;
            case 3 -> DOWN;
            case 4 -> FRONT;
            case 5 -> BACK;
            default -> throw new IllegalArgumentException("Invalid sector index: " + index);
        };
    }
}
