package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mixin into {@link Chunk} to extend the vanilla section array so the
 * renderer can see blocks at the planet surface Y level.
 *
 * PROBLEM:
 * The dimension type has min_y=0, height=16 → 1 section (Y=0..15).
 * Planet surface at Y=planetRadius (e.g. Y=7,000 for 14 km) is invisible.
 *
 * FIX:
 * Override getSectionArray() to return a virtual array sized up to the
 * planet surface section index (capped at 8192). Populate from
 * PlanetBlockStorage. Cache in a static WeakHashMap keyed by Chunk
 * so other mixins (MixinWorldChunk_UnloadCleanup) can invalidate.
 */
@Mixin(Chunk.class)
public class MixinWorldChunk_CubicWorld {

    /** Global cache: virtual section arrays keyed by Chunk identity. */
    @Unique
    private static final Map<Chunk, ChunkSection[]> blockyPlanet_virtualCache =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Per-section cache for the getSection(int) fallback. */
    @Unique
    private final Int2ObjectOpenHashMap<ChunkSection> blockyPlanet_sectionCache = new Int2ObjectOpenHashMap<>();

    /** Invalidate the virtual array cache for a specific chunk. */
    public static void invalidate(Chunk chunk) {
        blockyPlanet_virtualCache.remove(chunk);
    }

    /**
     * Override getSectionArray() to return a virtual section array that
     * includes sections up to the planet surface Y level.
     */
    @Inject(
        method = "getSectionArray()[Lnet/minecraft/world/chunk/ChunkSection;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void blockyPlanet_getSectionArray(CallbackInfoReturnable<ChunkSection[]> cir) {
        if (!((Object) this instanceof WorldChunk self)) return;
        World world = self.getWorld();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        Chunk chunk = (Chunk) (Object) this;

        // Check global cache
        ChunkSection[] cached = blockyPlanet_virtualCache.get(chunk);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        ChunkSection[] original = cir.getReturnValue();
        double r = QuadSphere.planetRadius();
        int surfaceIdx = (int) Math.ceil(r / 16.0);

        // Cap at 8192 sections (~131 km radius) to prevent excessive memory
        int maxIdx = Math.min(surfaceIdx, 8191);
        if (maxIdx < original.length) return; // Already covered

        // Build virtual array
        ChunkSection[] virtual = new ChunkSection[maxIdx + 1];
        System.arraycopy(original, 0, virtual, 0, original.length);

        // Fill higher sections from PlanetBlockStorage or empty
        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        Registry<Biome> biomeReg = world.getRegistryManager().get(RegistryKeys.BIOME);

        for (int i = original.length; i <= maxIdx; i++) {
            int cubeY = i; // section index == cubeY (both Y÷16)
            int baseY = i << 4;

            if (storage.hasAnyInSection(chunkX, cubeY, chunkZ)) {
                ChunkSection sec = new ChunkSection(biomeReg);
                boolean hasBlocks = false;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int dy = 0; dy < 16; dy++) {
                            BlockState state = storage.getBlockState(
                                chunkX * 16 + dx, baseY + dy, chunkZ * 16 + dz);
                            if (!state.isAir()) {
                                sec.setBlockState(dx, dy, dz, state, false);
                                hasBlocks = true;
                            }
                        }
                    }
                }
                if (hasBlocks) {
                    virtual[i] = sec;
                    continue;
                }
            }
            // Fill with empty section to avoid null sections
            virtual[i] = new ChunkSection(biomeReg);
        }

        blockyPlanet_virtualCache.put(chunk, virtual);
        cir.setReturnValue(virtual);
    }

    /**
     * Intercept getSection(int) as fallback for code that requests
     * individual sections outside the normal range.
     */
    @Inject(
        method = "getSection(I)Lnet/minecraft/world/chunk/ChunkSection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_getSection(int yIndex, CallbackInfoReturnable<ChunkSection> cir) {
        if (!((Object) this instanceof WorldChunk self)) return;
        World world = self.getWorld();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        // Check per-section cache
        ChunkSection cached = blockyPlanet_sectionCache.get(yIndex);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        // Check PlanetBlockStorage
        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        int baseY = yIndex << 4;

        if (!storage.hasAnyInSection(chunkX, yIndex, chunkZ)) {
            return; // Let vanilla handle it
        }

        Registry<Biome> biomeRegistry = world.getRegistryManager().get(RegistryKeys.BIOME);
        ChunkSection section = new ChunkSection(biomeRegistry);

        boolean hasBlocks = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dy = 0; dy < 16; dy++) {
                    BlockState state = storage.getBlockState(
                        chunkX * 16 + dx, baseY + dy, chunkZ * 16 + dz);
                    if (!state.isAir()) {
                        section.setBlockState(dx, dy, dz, state, false);
                        hasBlocks = true;
                    }
                }
            }
        }

        if (hasBlocks) {
            blockyPlanet_sectionCache.put(yIndex, section);
            cir.setReturnValue(section);
        }
    }
}
