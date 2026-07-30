package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link LevelHeightAccessor} to override build-height bounds
 * for the Blocky Planet dimension (NeoForge / Mojmap).
 *
 * Vanilla overworld: minBuildHeight=-64, maxBuildHeight=320 (384 blocks).
 * Our planet surface is at Y ≈ planetRadius (e.g. 7,015 for 14 km).
 * Feature placement, lighting, and other systems reject Y values outside
 * the build-height range, causing "empty height range" warnings and
 * preventing chunks from completing → world creation stuck at 100 %.
 *
 * By reporting minBuildHeight = -planetRadius - 128 and
 * maxBuildHeight = planetRadius + 128, all systems see a valid range
 * that includes the planet surface.
 *
 * The MixinWorldChunk_CubicWorld sparse section array prevents excessive
 * memory allocation despite the enlarged range.
 */
@Mixin(LevelHeightAccessor.class)
public interface MixinWorld_HeightRange {

    /**
     * Override {@code getMinBuildHeight()} so the world bottom lies below
     * the planet core. Vanilla overworld returns -64.
     */
    @Inject(
        method = "getMinBuildHeight",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_getMinBuildHeight(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof Level level)) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(level)) return;
        double planetR = QuadSphere.planetRadius();
        cir.setReturnValue(-(int) planetR - 128);
    }

    /**
     * Override {@code getMaxBuildHeight()} so the world top lies above
     * the planet surface. Vanilla overworld returns 320.
     */
    @Inject(
        method = "getMaxBuildHeight",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_getMaxBuildHeight(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof Level level)) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(level)) return;
        double planetR = QuadSphere.planetRadius();
        cir.setReturnValue((int) planetR + 128);
    }
}
