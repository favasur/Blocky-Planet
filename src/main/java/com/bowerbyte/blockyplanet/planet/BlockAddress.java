package com.bowerbyte.blockyplanet.planet;

/**
 * A block address specifies the exact location of a block within the planet's
 * hierarchical structure: Sector → Shell → Chunk → Block.
 *
 * This is analogous to a postal address: Country → State → City → Building.
 *
 * <pre>
 * BlockAddress {
 *     int sectorIndex;  // 0 - 5  (six cube faces)
 *     int shellIndex;   // 0 - n  (concentric 16-block-thick shells)
 *     Int3 chunkIndex;  // [0..cps, 0..vcs, 0..cps]  (16×16×16 cube)
 *     Int3 blockIndex;  // [0..15, 0..15, 0..15]
 * }
 * </pre>
 *
 * KEY INSIGHT: Each shell has its own resolution that doubles every shell,
 * so a single block at the surface of shell N maps to 4 blocks (2×2) at
 * the bottom of shell N+1.  This prevents the "squeezing" problem.
 */
public record BlockAddress(
    int sectorIndex,
    int shellIndex,
    Int3 chunkIndex,
    Int3 blockIndex
) {

    // ─── Factory: from world position ────────────────────────────────────────

    /**
     * Compute a BlockAddress from a world position relative to the planet center.
     *
     * Algorithm:
     * 1. Sector Index: find the axis with maximum absolute value → determines sector
     * 2. Shell Index: distance from origin → determines shell (and thus resolution)
     * 3. Project onto cube face → (u, v) in [-1, 1]
     * 4. Convert (u, v) and layer to global block indices, then to chunk+block
     */
    public static BlockAddress fromWorldPosition(Vector3d pos) {
        double radius = pos.length();
        if (radius < 1e-12) {
            // At the exact center — default to sector 0 (RIGHT), shell 0, block 0
            return new BlockAddress(0, 0, Int3.ZERO, Int3.ZERO);
        }

        int shellIdx = QuadSphere.getShellIndex(radius);
        int layerInShell = QuadSphere.getLayerInShell(radius);

        // 1. Sector
        int sectorIdx = SectorType.fromPosition(pos).index();

        // 2. Project onto the cube face to get (u, v) in [-1, 1]
        Vector2d uv = QuadSphere.projectToCubeFace(pos, sectorIdx);
        double u = uv.u();
        double v = uv.v();

        // 3. Convert to global block indices on the cube face
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIdx);
        // Remap from [-1, 1] to [0, blocksPerSide) and clamp
        int blockU = clamp((int) Math.floor((u + 1.0) * 0.5 * blocksPerSide), 0, blocksPerSide - 1);
        int blockV = clamp((int) Math.floor((v + 1.0) * 0.5 * blocksPerSide), 0, blocksPerSide - 1);

        // 4. Split into chunk and block indices
        int chunkX = blockU >> QuadSphere.CHUNK_BITS;
        int chunkZ = blockV >> QuadSphere.CHUNK_BITS;
        int chunkY = layerInShell >> QuadSphere.CHUNK_BITS;
        int localBlockX = blockU & QuadSphere.CHUNK_MASK;
        int localBlockY = layerInShell & QuadSphere.CHUNK_MASK;
        int localBlockZ = blockV & QuadSphere.CHUNK_MASK;

        return new BlockAddress(
            sectorIdx,
            shellIdx,
            new Int3(chunkX, chunkY, chunkZ),
            new Int3(localBlockX, localBlockY, localBlockZ)
        );
    }

    // ─── Convert address back to world position ─────────────────────────────

    /**
     * Convert this address back to an approximate world position.
     * Returns the center of the block.
     */
    public Vector3d toWorldPosition() {
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIndex);

        // Global block indices on the cube face
        int globalBlockX = (chunkIndex.x() << QuadSphere.CHUNK_BITS) + blockIndex.x();
        int globalBlockZ = (chunkIndex.z() << QuadSphere.CHUNK_BITS) + blockIndex.z();
        int globalBlockY = (chunkIndex.y() << QuadSphere.CHUNK_BITS) + blockIndex.y();

        // u, v in [-1, 1], centered on the block
        double u = (globalBlockX + 0.5) / blocksPerSide * 2.0 - 1.0;
        double v = (globalBlockZ + 0.5) / blocksPerSide * 2.0 - 1.0;

        // Radius: inner radius of shell + layer offset + half-block
        double radius = QuadSphere.getShellInnerRadius(shellIndex) + (globalBlockY + 0.5) * QuadSphere.BLOCK_SIZE;

        // Clamp u, v to [-1, 1]
        u = Math.max(-1.0, Math.min(1.0, u));
        v = Math.max(-1.0, Math.min(1.0, v));

        return QuadSphere.projectFromCubeFace(sectorIndex, u, v, radius);
    }

    /**
     * Get the world position of this block using the improved (reduced distortion) mapping.
     */
    public Vector3d toWorldPositionImproved() {
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIndex);

        int globalBlockX = (chunkIndex.x() << QuadSphere.CHUNK_BITS) + blockIndex.x();
        int globalBlockZ = (chunkIndex.z() << QuadSphere.CHUNK_BITS) + blockIndex.z();
        int globalBlockY = (chunkIndex.y() << QuadSphere.CHUNK_BITS) + blockIndex.y();

        double u = (globalBlockX + 0.5) / blocksPerSide * 2.0 - 1.0;
        double v = (globalBlockZ + 0.5) / blocksPerSide * 2.0 - 1.0;

        double radius = QuadSphere.getShellInnerRadius(shellIndex) + (globalBlockY + 0.5) * QuadSphere.BLOCK_SIZE;

        u = Math.max(-1.0, Math.min(1.0, u));
        v = Math.max(-1.0, Math.min(1.0, v));

        return QuadSphere.projectFromCubeFaceImproved(sectorIndex, u, v, radius);
    }

    // ─── Surface normal (for curved-block rendering) ────────────────────────

    /**
     * Return the outward surface normal (gravity-up direction) at this block's
     * world position.  This is a unit vector pointing away from the planet center.
     *
     * The normal is computed from the block's gravity-aligned position, so it
     * naturally follows the sphere's curvature even within a single chunk.
     * This enables a future renderer to slightly tilt each block's mesh so that
     * adjacent faces form a continuous curved surface instead of flat walls.
     *
     * @return unit vector pointing radially outward, or null if at exact center
     */
    public Vector3d getSurfaceNormal() {
        Vector3d pos = toWorldPositionImproved();
        double len = pos.length();
        if (len < 1e-12) return null;
        return pos.scale(1.0 / len);
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    /**
     * Check if this address is within the surface shell of the planet.
     */
    public boolean isSurface() {
        double outerRadius = QuadSphere.getShellOuterRadius(shellIndex);
        double planetRadius = QuadSphere.planetRadius();
        return Math.abs(outerRadius - planetRadius) < QuadSphere.LAYERS_PER_SHELL * QuadSphere.BLOCK_SIZE;
    }

    /**
     * Check if this address is valid (within bounds).
     */
    public boolean isValid() {
        if (sectorIndex < 0 || sectorIndex > 5) return false;
        if (shellIndex < 0) return false;

        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIndex);
        int globalBlockX = (chunkIndex.x() << QuadSphere.CHUNK_BITS) + blockIndex.x();
        int globalBlockY = (chunkIndex.y() << QuadSphere.CHUNK_BITS) + blockIndex.y();
        int globalBlockZ = (chunkIndex.z() << QuadSphere.CHUNK_BITS) + blockIndex.z();

        if (globalBlockX < 0 || globalBlockX >= blocksPerSide) return false;
        if (globalBlockZ < 0 || globalBlockZ >= blocksPerSide) return false;
        if (globalBlockY < 0 || globalBlockY >= QuadSphere.LAYERS_PER_SHELL) return false;

        return true;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return String.format("S%d/Sh%d/C(%d,%d,%d)/B(%d,%d,%d)",
            sectorIndex, shellIndex,
            chunkIndex.x(), chunkIndex.y(), chunkIndex.z(),
            blockIndex.x(), blockIndex.y(), blockIndex.z());
    }
}
