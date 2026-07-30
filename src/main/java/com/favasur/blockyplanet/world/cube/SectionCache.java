package com.favasur.blockyplanet.world.cube;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Standalone cache for virtual section arrays used by
 * {@link com.favasur.blockyplanet.mixin.MixinWorldChunk_CubicWorld}.
 *
 * Lives outside any mixin class so Mixin never tries to merge its
 * static members into a target class.
 */
public final class SectionCache {

    private SectionCache() {}

    /** Global cache: virtual section arrays keyed by Chunk identity. */
    private static final Map<ChunkAccess, LevelChunkSection[]> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Invalidate the virtual array cache for a specific chunk. */
    public static void invalidate(ChunkAccess chunk) {
        CACHE.remove(chunk);
    }

    /** Retrieve cached virtual array, or null if not cached. */
    public static LevelChunkSection[] get(ChunkAccess chunk) {
        return CACHE.get(chunk);
    }

    /** Store a virtual array for the given chunk. */
    public static void put(ChunkAccess chunk, LevelChunkSection[] sections) {
        CACHE.put(chunk, sections);
    }
}
