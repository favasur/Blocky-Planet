package com.favasur.blockyplanet.world.cube;

import com.favasur.blockyplanet.planet.Vector3d;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.Arrays;

/**
 * A sparse 3D block storage that stores blocks in 16×16×16 cubes,
 * keyed by their (cubeX, cubeY, cubeZ) cube-space position.
 *
 * Optionally stores a {@link Vector3d} surface normal per block,
 * representing the outward (gravity-up) direction at that block's
 * position on the planet sphere.  This enables a future renderer to
 * tilt each block's mesh so that adjacent faces form a continuous
 * curved surface — matching the "curved blocks at each chunk" design.
 */
public class PlanetBlockStorage {

    private final Long2ObjectOpenHashMap<BlockState[]> cubes = new Long2ObjectOpenHashMap<>();

    /** Optional surface normals (same indexing as cubes). May be null. */
    private final Long2ObjectOpenHashMap<Vector3d[]> normals = new Long2ObjectOpenHashMap<>();

    public PlanetBlockStorage() {}

    private static long key(int cx, int cy, int cz) {
        return ((long) cx & 0x1FFFFFL)
             | (((long) cy & 0x1FFFFFL) << 21)
             | (((long) cz & 0x1FFFFFL) << 42);
    }

    private static int blockIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    // ─── Block state access ──────────────────────────────────────────────────

    /**
     * All public methods are synchronized because the {@link #cubes} map is
     * accessed from multiple threads simultaneously:
     * - Worker threads in {@code populateNoise/fillFromNoise} call setBlockState
     * - The server thread calls removeAllForChunk on chunk unload
     *
     * {@link Long2ObjectOpenHashMap} is NOT thread-safe.
     */

    public synchronized BlockState getBlockState(int x, int y, int z) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        BlockState[] blocks = cubes.get(key(cx, cy, cz));
        if (blocks == null) return Blocks.AIR.getDefaultState();
        return blocks[blockIndex(x, y, z)];
    }

    public synchronized void setBlockState(int x, int y, int z, BlockState state) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        long k = key(cx, cy, cz);
        BlockState[] blocks = cubes.get(k);
        if (blocks == null) {
            blocks = new BlockState[4096];
            Arrays.fill(blocks, Blocks.AIR.getDefaultState());
            cubes.put(k, blocks);
        }
        blocks[blockIndex(x, y, z)] = state;
    }

    // ─── Surface normal access (for curved-block rendering) ──────────────────

    /**
     * Set the surface normal for a specific block.
     * The normal is the unit vector pointing radially away from the planet center
     * at this block's gravity-aligned position.
     */
    public synchronized void setNormal(int x, int y, int z, Vector3d normal) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        long k = key(cx, cy, cz);
        Vector3d[] ns = normals.get(k);
        if (ns == null) {
            ns = new Vector3d[4096];
            normals.put(k, ns);
        }
        ns[blockIndex(x, y, z)] = normal;
    }

    /**
     * Get the surface normal for a specific block.
     * Returns null if no normal was stored (e.g. for air blocks or un-generated cubes).
     */
    public synchronized Vector3d getNormal(int x, int y, int z) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        Vector3d[] ns = normals.get(key(cx, cy, cz));
        if (ns == null) return null;
        return ns[blockIndex(x, y, z)];
    }

    /**
     * Batch-set normals for an entire cube from its BlockAddress positions.
     */
    public synchronized void setCubeNormals(int cx, int cy, int cz, Vector3d[] normalsArray) {
        if (normalsArray == null || normalsArray.length != 4096) return;
        normals.put(key(cx, cy, cz), normalsArray);
    }

    // ─── Cube management ─────────────────────────────────────────────────────

    public synchronized boolean hasCube(int cx, int cy, int cz) {
        return cubes.containsKey(key(cx, cy, cz));
    }

    /**
     * Quick check if ANY cube exists in this chunk's XZ column at the given section Y.
     * Used by the WorldChunk section mixin to avoid creating empty ChunkSections.
     */
    public synchronized boolean hasAnyInSection(int chunkX, int sectionY, int chunkZ) {
        // Check the single cube at this Y level for this chunk's X,Z
        return cubes.containsKey(key(chunkX, sectionY, chunkZ));
    }

    public synchronized void removeCube(int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        cubes.remove(k);
        normals.remove(k);
    }

    /**
     * Remove ALL cubes that belong to the given chunk column (any Y level).
     * Called when a chunk unloads, preventing unbounded memory growth.
     *
     * @param chunkX X coordinate of the chunk (÷ 16)
     * @param chunkZ Z coordinate of the chunk (÷ 16)
     */
    public synchronized void removeAllForChunk(int chunkX, int chunkZ) {
        // Collect keys to remove while iterating
        var toRemove = new java.util.ArrayList<Long>();
        for (var it = cubes.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var entry = it.next();
            long k = entry.getLongKey();
            int cx = (int) (k & 0x1FFFFFL);
            if (cx != chunkX) continue;
            int cz = (int) ((k >> 42) & 0x1FFFFFL);
            if (cz != chunkZ) continue;
            toRemove.add(k);
        }
        for (long k : toRemove) {
            cubes.remove(k);
            normals.remove(k);
        }
    }

    public synchronized int size() {
        return cubes.size();
    }

    public synchronized void clear() {
        cubes.clear();
        normals.clear();
    }
}
