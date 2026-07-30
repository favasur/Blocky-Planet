package com.bowerbyte.blockyplanet.mixin;

import com.bowerbyte.blockyplanet.BlockyPlanetMod;
import com.bowerbyte.blockyplanet.world.cube.PlanetBlockStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link WorldChunk} to serve blocks from {@link PlanetBlockStorage}
 * to the vanilla renderer at ANY Y coordinate, not just Y=0..15.
 *
 * Without this mixin, blocks at Y > 15 are stored in PlanetBlockStorage but
 * never render because the vanilla section array only has 1 element
 * (since dimension height=16). The renderer calls getSection(yIndex) for
 * each section it wants to draw, and we intercept that to create
 * ChunkSection objects from our unbounded cube storage.
 */
@Mixin(WorldChunk.class)
public class MixinWorldChunk_CubicWorld {

    @Unique
    private final Int2ObjectOpenHashMap<ChunkSection> blockyPlanet_sectionCache = new Int2ObjectOpenHashMap<>();

    /**
     * Intercept {@code WorldChunk.getSection(int)} to serve blocks from
     * PlanetBlockStorage for section indices outside the vanilla height.
     */
    @Inject(
        method = "getSection(I)Lnet/minecraft/world/chunk/ChunkSection;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void blockyPlanet_getSection(int yIndex, CallbackInfoReturnable<ChunkSection> cir) {
        WorldChunk self = (WorldChunk) (Object) this;
        World world = self.getWorld();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        // Check section cache first
        ChunkSection cached = blockyPlanet_sectionCache.get(yIndex);
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

        // Create a new ChunkSection populated from PlanetBlockStorage
        Registry<Biome> biomeRegistry = world.getRegistryManager().get(RegistryKeys.BIOME);
        ChunkSection section = new ChunkSection(biomeRegistry);

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
