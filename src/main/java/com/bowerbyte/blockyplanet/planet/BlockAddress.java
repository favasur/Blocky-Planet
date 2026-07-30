package com.bowerbyte.blockyplanet.planet;

/**
 * A block address specifies the exact location of a block within the planet's
 * hierarchical structure: Sector → Shell → Chunk → Block.
 *
 * This is analogous to a postal address: Country → State → City → Building.
 *
 * From the blog post:
 * <pre>
 * struct BlockAddress {
 *     public int sectorIndex;  // 0 - 5
 *     public int shellIndex;   // 0 - +Infinity
 *     public int3 chunkIndex;  // [0-n, 0-n, 0-n]
 *     public int3 blockIndex;  // [0-15, 0-15, 0-15]
 * }
 * </pre>
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
     * Algorithm (from the blog post):
     * 1. Sector Index: find the axis with maximum absolute value → determines sector
     * 2. Shell Index: distance from origin → determines shell
     * 3. Chunk Index: reverse-project onto cube face → normalize → remap to chunks
     * 4. Block Index: same as chunk but remap to blocks, then mod chunk size
     */
    public static BlockAddress fromWorldPosition(Vector3d pos) {
        double radius = pos.length();
        int shellIdx = QuadSphere.getShellIndex(radius);

        // 1. Sector
        SectorType sector = SectorType.fromPosition(pos);
        int sectorIdx = sector.index();

        // 2. Shell
        double shellInnerRadius = QuadSphere.getShellInnerRadius(shellIdx);
        double layerOffset = radius - shellInnerRadius;
        double localLayer = layerOffset / QuadSphere.BLOCK_SIZE;
        int layer = Math.max(0, Math.min(QuadSphere.LAYERS_PER_SHELL - 1, (int) localLayer));

        // 3. Chunk index from projected uv
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIdx);
        int chunksPerSide = QuadSphere.getChunksPerSide(shellIdx);
        int verticalChunks = QuadSphere.getVerticalChunks(shellIdx);

        // Project onto the cube face to get (u, v) in [-1, 1]
        Vector2d uv = QuadSphere.projectToCubeFace(pos, sectorIdx);

        // Remap (u, v) from [-1, 1] to [0, blocksPerSide)
        double blockU = (uv.u() + 1.0) * 0.5 * blocksPerSide;
        double blockV = (uv.v() + 1.0) * 0.5 * blocksPerSide;

        // Clamp to valid range
        blockU = Math.max(0, Math.min(blocksPerSide - 1, blockU));
        blockV = Math.max(0, Math.min(blocksPerSide - 1, blockV));

        // Chunk indices
        int chunkX = (int) blockU / QuadSphere.CHUNK_SIZE;
        int chunkZ = (int) blockV / QuadSphere.CHUNK_SIZE;
        int chunkY = layer / QuadSphere.CHUNK_SIZE;

        chunkX = Math.max(0, Math.min(chunksPerSide - 1, chunkX));
        chunkZ = Math.max(0, Math.min(chunksPerSide - 1, chunkZ));
        chunkY = Math.max(0, Math.min(verticalChunks - 1, chunkY));

        // Block indices within chunk
        int blockX = (int) blockU % QuadSphere.CHUNK_SIZE;
        int blockZ = (int) blockV % QuadSphere.CHUNK_SIZE;
        int blockY = layer % QuadSphere.CHUNK_SIZE;

        return new BlockAddress(
            sectorIdx,
            shellIdx,
            new Int3(chunkX, chunkY, chunkZ),
            new Int3(blockX, blockY, blockZ)
        );
    }

    /**
     * Convert this address back to an approximate world position.
     * Returns the center of the block.
     */
    public Vector3d toWorldPosition() {
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIndex);

        // Reconstruct the block's (u, v) on the cube face
        int totalBlockX = chunkIndex.x() * QuadSphere.CHUNK_SIZE + blockIndex.x();
        int totalBlockY = chunkIndex.y() * QuadSphere.CHUNK_SIZE + blockIndex.y();
        int totalBlockZ = chunkIndex.z() * QuadSphere.CHUNK_SIZE + blockIndex.z();

        // u, v in [-1, 1]
        double u = (totalBlockX + 0.5) / blocksPerSide * 2.0 - 1.0;
        double v = (totalBlockZ + 0.5) / blocksPerSide * 2.0 - 1.0;

        // Radius
        double layer = totalBlockY + 0.5;
        double radius = QuadSphere.getShellInnerRadius(shellIndex) + layer * QuadSphere.BLOCK_SIZE;

        return QuadSphere.projectFromCubeFace(sectorIndex, u, v, radius);
    }

    /**
     * Get the world position of this block using the improved (reduced distortion) mapping.
     */
    public Vector3d toWorldPositionImproved() {
        int blocksPerSide = QuadSphere.getBlocksPerSide(shellIndex);

        int totalBlockX = chunkIndex.x() * QuadSphere.CHUNK_SIZE + blockIndex.x();
        int totalBlockY = chunkIndex.y() * QuadSphere.CHUNK_SIZE + blockIndex.y();
        int totalBlockZ = chunkIndex.z() * QuadSphere.CHUNK_SIZE + blockIndex.z();

        double u = (totalBlockX + 0.5) / blocksPerSide * 2.0 - 1.0;
        double v = (totalBlockZ + 0.5) / blocksPerSide * 2.0 - 1.0;

        double layer = totalBlockY + 0.5;
        double radius = QuadSphere.getShellInnerRadius(shellIndex) + layer * QuadSphere.BLOCK_SIZE;

        return QuadSphere.projectFromCubeFaceImproved(sectorIndex, u, v, radius);
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
        int totalBlockX = chunkIndex.x() * QuadSphere.CHUNK_SIZE + blockIndex.x();
        int totalBlockY = chunkIndex.y() * QuadSphere.CHUNK_SIZE + blockIndex.y();
        int totalBlockZ = chunkIndex.z() * QuadSphere.CHUNK_SIZE + blockIndex.z();

        if (totalBlockX < 0 || totalBlockX >= blocksPerSide) return false;
        if (totalBlockZ < 0 || totalBlockZ >= blocksPerSide) return false;
        if (totalBlockY < 0 || totalBlockY >= QuadSphere.LAYERS_PER_SHELL) return false;

        return true;
    }

    @Override
    public String toString() {
        return String.format("S%d/Sh%d/C(%d,%d,%d)/B(%d,%d,%d)",
            sectorIndex, shellIndex,
            chunkIndex.x(), chunkIndex.y(), chunkIndex.z(),
            blockIndex.x(), blockIndex.y(), blockIndex.z());
    }
}
