package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import com.favasur.blockyplanet.world.cube.SectionCache;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link ChunkAccess} to extend the vanilla section array so the
 * renderer can see blocks at the planet surface Y level (NeoForge / Mojmap).
 */
@Mixin(ChunkAccess.class)
public class MixinWorldChunk_CubicWorld {

    @Unique
    private final Int2ObjectOpenHashMap<LevelChunkSection> blockyPlanet_sectionCache = new Int2ObjectOpenHashMap<>();

    /**
     * Override getSections() to return a virtual section array that
     * includes sections up to the planet surface Y level.
     */
    @Inject(
        method = "getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void blockyPlanet_getSections(CallbackInfoReturnable<LevelChunkSection[]> cir) {
        if (!((Object) this instanceof LevelChunk self)) return;
        Level world = self.getLevel();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        ChunkAccess chunk = (ChunkAccess) (Object) this;

        LevelChunkSection[] cached = SectionCache.get(chunk);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        LevelChunkSection[] original = cir.getReturnValue();
        double r = QuadSphere.planetRadius();
        int surfaceIdx = (int) Math.ceil(r / 16.0);
        int maxIdx = Math.min(surfaceIdx, 8191);
        if (maxIdx < original.length) return;

        LevelChunkSection[] virtual = new LevelChunkSection[maxIdx + 1];
        System.arraycopy(original, 0, virtual, 0, original.length);

        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        Registry<Biome> biomeReg = world.registryAccess().registryOrThrow(Registries.BIOME);

        for (int i = original.length; i <= maxIdx; i++) {
            int cubeY = i;
            int baseY = i << 4;

            if (storage.hasAnyInSection(chunkX, cubeY, chunkZ)) {
                LevelChunkSection sec = new LevelChunkSection(biomeReg);
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
            virtual[i] = new LevelChunkSection(biomeReg);
        }

        SectionCache.put(chunk, virtual);
        cir.setReturnValue(virtual);
    }

    @Inject(
        method = "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_getSection(int yIndex, CallbackInfoReturnable<LevelChunkSection> cir) {
        if (!((Object) this instanceof LevelChunk self)) return;
        Level world = self.getLevel();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        LevelChunkSection cached = blockyPlanet_sectionCache.get(yIndex);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        int baseY = yIndex << 4;

        Registry<Biome> biomeReg = world.registryAccess().registryOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(biomeReg);

        boolean hasBlocks = false;
        if (storage.hasAnyInSection(chunkX, yIndex, chunkZ)) {
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
        }

        // Always cache and return the section, even if empty.
        // Falling through to vanilla getSection would throw
        // ArrayIndexOutOfBoundsException for yIndex outside the
        // vanilla section array bounds.
        blockyPlanet_sectionCache.put(yIndex, section);
        cir.setReturnValue(section);
    }
}
