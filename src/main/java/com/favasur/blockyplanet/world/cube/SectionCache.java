package com.favasur.blockyplanet.world.cube;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

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
    private static final Map<Chunk, ChunkSection[]> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Invalidate the virtual array cache for a specific chunk. */
    public static void invalidate(Chunk chunk) {
        CACHE.remove(chunk);
    }

    /** Retrieve cached virtual array, or null if not cached. */
    public static ChunkSection[] get(Chunk chunk) {
        return CACHE.get(chunk);
    }

    /** Store a virtual array for the given chunk. */
    public static void put(Chunk chunk, ChunkSection[] sections) {
        CACHE.put(chunk, sections);
    }
}
