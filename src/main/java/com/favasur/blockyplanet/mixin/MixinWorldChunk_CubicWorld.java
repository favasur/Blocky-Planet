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
 * Uses a SPARSE virtual array: only creates sections within
 * VERTICAL_RANGE of the planet surface Y. Sections outside that
 * window are null (renderer skips nulls). This prevents massive
 * arrays for Earth-sized planets while keeping surface blocks visible.
 *
 * For planets where the surface is beyond VERTICAL_RANGE from
 * the vanilla array (radius > 256 blocks), the virtual array
 * shifts the window to cover the surface.
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

    /**
     * Number of sections above and below the planet surface to include
     * in the virtual array. 512 sections = 8192 blocks vertically.
     * Enough to cover crust + nether ring + lava on any planet size.
     */
    @Unique
    private static final int VERTICAL_RANGE = 512;

    /** Invalidate the virtual array cache for a specific chunk. */
    public static void invalidate(Chunk chunk) {
        blockyPlanet_virtualCache.remove(chunk);
    }

    /**
     * Override getSectionArray() to return a virtual array with sections
     * near the planet surface Y level. Sections far from the surface are
     * null (renderer skips nulls).
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
        double planetRadius = QuadSphere.planetRadius();
        int surfaceSection = (int) Math.floor(planetRadius / 16.0);

        // Define vertical window: VERTICAL_RANGE sections above and below surface
        int windowMin = surfaceSection - VERTICAL_RANGE;
        int windowMax = surfaceSection + VERTICAL_RANGE;

        // Clamp to non-negative
        if (windowMin < 0) windowMin = 0;

        // If the window is within the original array, no modification needed
        if (windowMax < original.length) return;

        // Build virtual array sized to cover the window
        int arraySize = windowMax + 1;
        // Hard cap at 1M sections (16M blocks) to prevent catastrophic memory
        if (arraySize > 1_000_000) {
            BlockyPlanetMod.LOGGER.warn(
                "Planet too large for virtual section array ({} sections). Surface won't render.",
                arraySize);
            return;
        }

        ChunkSection[] virtual = new ChunkSection[arraySize];

        // Copy vanilla sections for the overlap range
        for (int i = 0; i < original.length && i < arraySize; i++) {
            virtual[i] = original[i];
        }

        // Only populate sections within the vertical window
        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        Registry<Biome> biomeReg = world.getRegistryManager().get(RegistryKeys.BIOME);

        int fillStart = Math.max(original.length, windowMin);
        int fillEnd = windowMax;

        for (int i = fillStart; i <= fillEnd; i++) {
            int cubeY = i;
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
                if (hasBlocks) virtual[i] = sec;
            }
            // No section → null (renderer skips null entries)
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

        ChunkSection cached = blockyPlanet_sectionCache.get(yIndex);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        int baseY = yIndex << 4;

        if (!storage.hasAnyInSection(chunkX, yIndex, chunkZ)) {
            return;
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
