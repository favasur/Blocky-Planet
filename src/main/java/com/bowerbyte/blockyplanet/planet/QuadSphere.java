package com.bowerbyte.blockyplanet.planet;

import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;

/**
 * Core math for the quad sphere planet structure.
 *
 * A quad sphere is constructed by taking a cube, subdividing each face into a grid,
 * and normalizing (inflating) each vertex onto the unit sphere.
 *
 * The planet is divided into:
 * - 6 sectors (cube faces)
 * - Concentric shells (groups of layers with increasing resolution)
 * - 16×16×16 block chunks within each shell
 *
 * KEY INSIGHT: Each shell doubles its linear resolution, so blocks stay roughly
 * square-shaped at every depth.  Without this, blocks near the planet's center
 * would be squeezed thin (like pizza slices converging at the core).
 *
 * Shells are 16 blocks thick (radially).  A shell's side-resolution doubles
 * every shell, starting from 16 blocks per side at shell 0.
 *
 * Reference: https://www.bowerbyte.com/posts/blocky-planet/
 */
public final class QuadSphere {

    // ─── Constants ───────────────────────────────────────────────────────────

    /** Base block size (1 block = 1 unit). */
    public static final double BLOCK_SIZE = 1.0;

    /** Chunks are cubes of CHUNK_SIZE blocks per side. */
    public static final int CHUNK_SIZE = 16;

    /** Bits to shift for chunk → block conversion (log2 of CHUNK_SIZE). */
    public static final int CHUNK_BITS = 4;

    /** Mask for extracting block index within a chunk. */
    public static final int CHUNK_MASK = CHUNK_SIZE - 1;

    /**
     * Base number of blocks per sector side on the innermost shell.
     * Shell 0 has BASE_RESOLUTION × BASE_RESOLUTION blocks per sector face.
     */
    public static final int BASE_RESOLUTION = 16;

    /**
     * Number of block layers (radial depth) per shell.
     * Each shell is LAYERS_PER_SHELL blocks thick.
     */
    public static final int LAYERS_PER_SHELL = 16;

    // ─── Planet radius (delegates to config) ────────────────────────────────

    /** Planet radius in blocks (half the configured diameter). */
    public static double planetRadius() {
        return BlockyPlanetConfig.getPlanetRadius();
    }

    // ─── Shell Math ──────────────────────────────────────────────────────────

    /**
     * Returns the number of blocks along one side of a sector face for the given shell.
     * Each successive shell doubles the linear resolution:
     *   shell 0 → 16, shell 1 → 32, shell 2 → 64, shell N → 16 × 2ᴺ
     */
    public static int getBlocksPerSide(int shellIndex) {
        return BASE_RESOLUTION * (1 << shellIndex);
    }

    /**
     * Total blocks per sector face layer in this shell.
     */
    public static int getBlocksPerLayer(int shellIndex) {
        int bps = getBlocksPerSide(shellIndex);
        return bps * bps;
    }

    /**
     * Total blocks in a single layer across all 6 sectors.
     */
    public static int getTotalBlocksPerLayer(int shellIndex) {
        return getBlocksPerLayer(shellIndex) * 6;
    }

    /**
     * Inner radius of a shell (distance from planet center to bottom of shell).
     * Shell 0 starts at radius 0 (planet center).
     * Shell N starts at radius N × LAYERS_PER_SHELL.
     */
    public static double getShellInnerRadius(int shellIndex) {
        return shellIndex * LAYERS_PER_SHELL * BLOCK_SIZE;
    }

    /**
     * Outer radius of a shell.
     */
    public static double getShellOuterRadius(int shellIndex) {
        return (shellIndex + 1) * LAYERS_PER_SHELL * BLOCK_SIZE;
    }

    /**
     * Thickness (radial depth) of a shell in blocks.
     */
    public static double getShellThickness() {
        return LAYERS_PER_SHELL * BLOCK_SIZE;
    }

    /**
     * Find which shell contains a given radius (distance from center).
     */
    public static int getShellIndex(double radius) {
        if (radius <= 0) return 0;
        return (int) Math.floor(radius / (LAYERS_PER_SHELL * BLOCK_SIZE));
    }

    /**
     * Get the local layer index within a shell for a given radius.
     * 0 = bottom of shell (closest to center), LAYERS_PER_SHELL-1 = top.
     */
    public static int getLayerInShell(double radius) {
        double shellBottom = getShellInnerRadius(getShellIndex(radius));
        int layer = (int) ((radius - shellBottom) / BLOCK_SIZE);
        return Math.max(0, Math.min(LAYERS_PER_SHELL - 1, layer));
    }

    // ─── Sector / Projection Math ────────────────────────────────────────────

    /**
     * Given a position relative to the planet center, project it onto the
     * corresponding cube face and return the (u, v) coordinates in [-1, 1].
     */
    public static Vector2d projectToCubeFace(Vector3d pos, int sectorIndex) {
        int axis = sectorIndex / 2;
        boolean positive = sectorIndex % 2 == 0;

        double faceDist;
        double u, v;

        switch (axis) {
            case 0 -> {
                faceDist = pos.x();
                u = pos.y();
                v = pos.z();
            }
            case 1 -> {
                faceDist = pos.y();
                u = pos.x();
                v = pos.z();
            }
            case 2 -> {
                faceDist = pos.z();
                u = pos.x();
                v = pos.y();
            }
            default ->
                throw new IllegalArgumentException("Invalid sector index: " + sectorIndex);
        }

        if (!positive) {
            faceDist = -faceDist;
            u = -u;
        }

        if (Math.abs(faceDist) < 1e-12) {
            return new Vector2d(0, 0);
        }

        double invDist = 1.0 / faceDist;
        u *= invDist;
        v *= invDist;

        u = Math.max(-1.0, Math.min(1.0, u));
        v = Math.max(-1.0, Math.min(1.0, v));

        return new Vector2d(u, v);
    }

    /**
     * Inverse projection: given a sector and (u, v) in [-1, 1] on the cube face,
     * plus a radius, compute the corresponding 3D position.
     */
    public static Vector3d projectFromCubeFace(int sectorIndex, double u, double v, double radius) {
        int axis = sectorIndex / 2;
        boolean positive = sectorIndex % 2 == 0;

        double uAdj = positive ? u : -u;

        double fx, fy, fz;
        switch (axis) {
            case 0 -> { fx = 1.0; fy = uAdj; fz = v; }
            case 1 -> { fx = uAdj; fy = 1.0; fz = v; }
            case 2 -> { fx = uAdj; fy = v; fz = 1.0; }
            default -> throw new IllegalArgumentException("Invalid sector index: " + sectorIndex);
        }

        if (!positive) {
            fx = -fx;
        }

        return new Vector3d(fx, fy, fz).normalize().scale(radius);
    }

    // ─── Chunk Math ──────────────────────────────────────────────────────────

    /**
     * Number of chunks along one side of a sector face in this shell.
     * E.g., shell 0 → 16/16 = 1 chunk per side; shell 1 → 32/16 = 2 chunks per side.
     */
    public static int getChunksPerSide(int shellIndex) {
        return getBlocksPerSide(shellIndex) / CHUNK_SIZE;
    }

    /**
     * Total number of 16×16 chunks per sector-face layer in this shell.
     */
    public static int getChunksPerLayer(int shellIndex) {
        int cps = getChunksPerSide(shellIndex);
        return cps * cps;
    }

    /**
     * Number of 16-block-tall vertical chunks in this shell.
     * Each shell has exactly LAYERS_PER_SHELL = 16 layers, so 1 vertical chunk.
     */
    public static int getVerticalChunks() {
        return LAYERS_PER_SHELL / CHUNK_SIZE;  // 1
    }

    /**
     * Total number of 16×16×16 chunks in one sector of a shell.
     */
    public static int getChunksPerSector(int shellIndex) {
        return getChunksPerLayer(shellIndex) * getVerticalChunks();
    }

    /**
     * Total number of 16×16×16 chunks in an entire shell (all 6 sectors).
     */
    public static int getTotalChunksInShell(int shellIndex) {
        return getChunksPerSector(shellIndex) * 6;
    }

    // ─── Max shell index ─────────────────────────────────────────────────────

    /**
     * The highest shell index that fits within the planet.
     */
    public static int getMaxShellIndex() {
        return getShellIndex(planetRadius());
    }

    // ─── Improved Mapping (reduced distortion) ───────────────────────────────

    /**
     * Apply the "improved mapping" that pre-distorts vertices on the cube face
     * to counteract the distortion introduced by spherical normalization.
     *
     * Reference: https://mathproofs.blogspot.com/2005/07/mapping-cube-to-sphere.html
     */
    public static Vector3d improvedMapping(Vector3d cubeVertex) {
        double x = cubeVertex.x();
        double y = cubeVertex.y();
        double z = cubeVertex.z();

        double x2 = x * x;
        double y2 = y * y;
        double z2 = z * z;

        double sx = Math.sqrt(1.0 - y2 / 2.0 - z2 / 2.0 + y2 * z2 / 3.0);
        double sy = Math.sqrt(1.0 - z2 / 2.0 - x2 / 2.0 + z2 * x2 / 3.0);
        double sz = Math.sqrt(1.0 - x2 / 2.0 - y2 / 2.0 + x2 * y2 / 3.0);

        return new Vector3d(x * sx, y * sy, z * sz).normalize();
    }

    /**
     * Project from cube face using the improved mapping (reduced distortion).
     */
    public static Vector3d projectFromCubeFaceImproved(int sectorIndex, double u, double v, double radius) {
        int axis = sectorIndex / 2;
        boolean positive = sectorIndex % 2 == 0;

        double uAdj = positive ? u : -u;

        double fx, fy, fz;
        switch (axis) {
            case 0 -> { fx = 1.0; fy = uAdj; fz = v; }
            case 1 -> { fx = uAdj; fy = 1.0; fz = v; }
            case 2 -> { fx = uAdj; fy = v; fz = 1.0; }
            default -> throw new IllegalArgumentException("Invalid sector index: " + sectorIndex);
        }

        if (!positive) {
            fx = -fx;
        }

        return improvedMapping(new Vector3d(fx, fy, fz)).scale(radius);
    }
}
