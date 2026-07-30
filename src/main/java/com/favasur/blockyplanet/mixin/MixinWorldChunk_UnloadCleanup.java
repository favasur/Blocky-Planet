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
 * {@link PlanetBlockStorage} cubes when a chunk unloads.
 *
 * Without this mixin, cubes accumulate in PlanetBlockStorage forever,
 * causing unbounded memory growth and disk I/O whenever the player
 * teleports or explores new areas.
 */
@Mixin(WorldChunk.class)
public class MixinWorldChunk_UnloadCleanup {

    @Inject(
        method = "setLoadedToWorld(Z)V",
        at = @At("HEAD")
    )
    private void blockyPlanet_onSetLoaded(boolean loaded, CallbackInfo ci) {
        if (loaded) return; // Only act on unload (loaded=false)

        WorldChunk self = (WorldChunk) (Object) this;
        World world = self.getWorld();
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        try {
            PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage(world);
            storage.removeAllForChunk(self.getPos().x, self.getPos().z);
        } catch (Exception ignored) {
            // Silently ignore — storage might not be initialized yet
        }
    }
}
