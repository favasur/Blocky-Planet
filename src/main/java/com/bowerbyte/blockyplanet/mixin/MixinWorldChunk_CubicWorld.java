package com.bowerbyte.blockyplanet.mixin;

import com.bowerbyte.blockyplanet.BlockyPlanetMod;
import com.bowerbyte.blockyplanet.world.cube.PlanetBlockStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link LevelChunk} to serve blocks from {@link PlanetBlockStorage}
 * to the vanilla renderer at ANY Y coordinate, not just Y=0..15.
 *
 * Without this mixin, blocks at Y > 15 are stored in PlanetBlockStorage but
 * never render because the vanilla section array only has 1 element
 * (since dimension height=16). The renderer calls getSection(yIndex) for
 * each section it wants to draw, and we intercept that to create
 * LevelChunkSection objects from our unbounded cube storage.
 */
@Mixin(LevelChunk.class)
public class MixinWorldChunk_CubicWorld {

    @Unique
    private final Int2ObjectOpenHashMap<LevelChunkSection> blockyPlanet_sectionCache = new Int2ObjectOpenHashMap<>();

    /**
     * Intercept {@code LevelChunk.getSection(int)} to serve blocks from
     * PlanetBlockStorage for section indices outside the vanilla height.
     */
    @Inject(
        method = "getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_getSection(int yIndex, CallbackInfoReturnable<LevelChunkSection> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        Level world = self.getLevel();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        // Check section cache first
        LevelChunkSection cached = blockyPlanet_sectionCache.get(yIndex);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        // Check if PlanetBlockStorage has any blocks for this section
        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
        int chunkX = self.getPos().x;
        int chunkZ = self.getPos().z;
        int baseY = yIndex << 4;

        // Quick check: does the storage have any cubes in this section's Y range?
        int cubeY = baseY >> 4;
        if (!storage.hasAnyInSection(chunkX, cubeY, chunkZ)) {
            return; // Let vanilla handle it (returns empty section)
        }

        // Create a new LevelChunkSection populated from PlanetBlockStorage
        Registry<Biome> biomeRegistry = world.registryAccess().lookupOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(biomeRegistry);

        boolean hasBlocks = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dy = 0; dy < 16; dy++) {
                    BlockState state = storage.getBlockState(
                        chunkX * 16 + dx,
                        baseY + dy,
                        chunkZ * 16 + dz
                    );
                    if (state != null && !state.isAir()) {
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
        // If no blocks in this section, fall through to vanilla (empty section)
    }


}
