package com.favasur.blockyplanet.world.cube;

import com.favasur.blockyplanet.planet.Vector3d;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

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
 *
 * All public methods are synchronized because the {@link #cubes} map is
 * accessed from multiple threads simultaneously:
 * - Worker threads in fillFromNoise call setBlockState
 * - The server thread calls removeAllForChunk on chunk unload
 * {@link Long2ObjectOpenHashMap} is NOT thread-safe.
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

    public synchronized BlockState getBlockState(int x, int y, int z) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        BlockState[] blocks = cubes.get(key(cx, cy, cz));
        if (blocks == null) return Blocks.AIR.defaultBlockState();
        return blocks[blockIndex(x, y, z)];
    }

    public synchronized void setBlockState(int x, int y, int z, BlockState state) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        long k = key(cx, cy, cz);
        BlockState[] blocks = cubes.get(k);
        if (blocks == null) {
            blocks = new BlockState[4096];
            Arrays.fill(blocks, Blocks.AIR.defaultBlockState());
            cubes.put(k, blocks);
        }
        blocks[blockIndex(x, y, z)] = state;
    }

    // ─── Surface normal access (for curved-block rendering) ──────────────────

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

    public synchronized Vector3d getNormal(int x, int y, int z) {
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        Vector3d[] ns = normals.get(key(cx, cy, cz));
        if (ns == null) return null;
        return ns[blockIndex(x, y, z)];
    }

    public synchronized void setCubeNormals(int cx, int cy, int cz, Vector3d[] normalsArray) {
        if (normalsArray == null || normalsArray.length != 4096) return;
        normals.put(key(cx, cy, cz), normalsArray);
    }

    // ─── Cube management ─────────────────────────────────────────────────────

    public synchronized boolean hasCube(int cx, int cy, int cz) {
        return cubes.containsKey(key(cx, cy, cz));
    }

    public synchronized boolean hasAnyInSection(int chunkX, int sectionY, int chunkZ) {
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
     */
    public synchronized void removeAllForChunk(int chunkX, int chunkZ) {
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

    /**
     * Fill a rectangular volume with a block state, creating any
     * missing cubes along the way. Much faster than calling
     * setBlockState for each individual block position.
     */
    public synchronized void fillVolume(int minX, int minY, int minZ,
                                         int maxX, int maxY, int maxZ,
                                         BlockState state) {
        int minCX = minX >> 4, maxCX = maxX >> 4;
        int minCY = minY >> 4, maxCY = maxY >> 4;
        int minCZ = minZ >> 4, maxCZ = maxZ >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    long k = key(cx, cy, cz);
                    BlockState[] blocks = cubes.get(k);
                    if (blocks == null) {
                        blocks = new BlockState[4096];
                        cubes.put(k, blocks);
                    }
                    Arrays.fill(blocks, state);
                }
            }
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
