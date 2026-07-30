package com.bowerbyte.blockyplanet.mixin;

import com.bowerbyte.blockyplanet.BlockyPlanetMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link WorldBorder} so that the Blocky Planet dimension
 * has no boundary — any position, no matter how far from origin, is
 * considered "inside" the border. This enables compatibility with
 * space-travel mods such as Cosmic Horizons.
 *
 * Only affects WorldBorder instances registered in
 * {@link BlockyPlanetMod#BLOCKY_BORDERS}, so other dimensions
 * (Overworld, Nether, End) keep their normal world borders.
 *
 * Registration happens when the World is initialized — see the
 * {@link MixinLevel_CubicWorld#blockyPlanet_ensureInit} method.
 */
@Mixin(WorldBorder.class)
public abstract class MixinWorldBorder_CubicWorld {

    /**
     * {@link WorldBorder#contains(BlockPos)} — always true for Blocky Planet.
     */
    @Inject(
        method = "contains(Lnet/minecraft/util/math/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void blockyPlanet_contains(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (BlockyPlanetMod.BLOCKY_BORDERS.contains(this)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * {@link WorldBorder#contains(double, double)} — always true for Blocky Planet.
     */
    @Inject(
        method = "contains(DD)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void blockyPlanet_contains(double x, double z, CallbackInfoReturnable<Boolean> cir) {
        if (BlockyPlanetMod.BLOCKY_BORDERS.contains(this)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * {@link WorldBorder#contains(double, double, double)} — always true for Blocky Planet.
     */
    @Inject(
        method = "contains(DDD)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void blockyPlanet_contains(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (BlockyPlanetMod.BLOCKY_BORDERS.contains(this)) {
            cir.setReturnValue(true);
        }
    }
}
