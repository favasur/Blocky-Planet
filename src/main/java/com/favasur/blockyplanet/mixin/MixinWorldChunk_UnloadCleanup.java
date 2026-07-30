package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link WorldChunk#setLoadedToWorld(boolean)} to clean up
 * {@link PlanetBlockStorage} cubes when a chunk unloads, and invalidate
 * the virtual section array cache when a chunk loads.
 */
@Mixin(WorldChunk.class)
public class MixinWorldChunk_UnloadCleanup {

    @Inject(
        method = "setLoadedToWorld(Z)V",
        at = @At("HEAD")
    )
    private void blockyPlanet_onSetLoaded(boolean loaded, CallbackInfo ci) {
        WorldChunk self = (WorldChunk) (Object) this;
        World world = self.getWorld();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        if (loaded) {
            // Chunk loading: invalidate virtual section array cache
            // so the renderer picks up any updated blocks
            MixinWorldChunk_CubicWorld.invalidate(self);
        } else {
            // Chunk unloading: clean up PlanetBlockStorage cubes
            try {
                PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
                storage.removeAllForChunk(self.getPos().x, self.getPos().z);
            } catch (Exception ignored) {}
        }
    }
}
