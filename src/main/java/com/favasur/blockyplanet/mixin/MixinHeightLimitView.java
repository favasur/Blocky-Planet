package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link HeightLimitView} to remove height limits for the
 * Blocky Planet dimension. This interface defines the default methods
 * {@code isOutOfHeightLimit(BlockPos)} and {@code isOutOfHeightLimit(int)}
 * which are inherited by {@link World} (among others).
 *
 * Without this mixin, the game considers any Y coordinate outside
 * -64…320 as "out of height limit", preventing block interactions
 * (breaking/placing) on the planet surface where Y ≈ planetRadius.
 *
 * The mixin checks {@link BlockyPlanetMod#isBlockyPlanetDimension}
 * before cancelling, so other dimensions retain their normal limits.
 */
@Mixin(HeightLimitView.class)
public abstract class MixinHeightLimitView {

    /**
     * {@code isOutOfHeightLimit(BlockPos)} — always false for Blocky Planet.
     */
    @Inject(
        method = "isOutOfHeightLimit(Lnet/minecraft/util/math/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_isOutOfHeightLimit(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof World world && BlockyPlanetMod.isBlockyPlanetDimension(world)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * {@code isOutOfHeightLimit(int)} — always false for Blocky Planet.
     */
    @Inject(
        method = "isOutOfHeightLimit(I)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_isOutOfHeightLimit(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof World world && BlockyPlanetMod.isBlockyPlanetDimension(world)) {
            cir.setReturnValue(false);
        }
    }
}
