package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link LevelHeightAccessor} (Mojmap equivalent of Fabric's
 * {@code HeightLimitView}) to remove height limits for the Blocky Planet
 * overworld.
 *
 * The {@code isOutsideBuildHeight} methods are default interface methods.
 * Mixin requires this to be an interface with default methods when the
 * target is itself an interface.
 */
@Mixin(LevelHeightAccessor.class)
public interface MixinHeightLimitView {

    /**
     * {@code isOutsideBuildHeight(BlockPos)} — always false for Blocky Planet.
     */
    @Inject(
        method = "isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_isOutsideBuildHeight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level && BlockyPlanetMod.isBlockyPlanetDimension(level)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code isOutsideBuildHeight(int)} — always false for Blocky Planet.
     */
    @Inject(
        method = "isOutsideBuildHeight(I)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_isOutsideBuildHeight(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof Level level && BlockyPlanetMod.isBlockyPlanetDimension(level)) {
            cir.setReturnValue(false);
        }
    }
}
