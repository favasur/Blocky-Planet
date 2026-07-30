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
 * Blocky Planet dimension.
 *
 * IMPORTANT: This mixin is an {@code interface} with {@code default} methods
 * because Mixin needs {@code SubType.Interface} when the target is itself an
 * interface. If declared as a class, Mixin's {@code SubType.Standard} rejects
 * it with "target type mismatch: ... is an interface".
 *
 * The {@code isOutOfHeightLimit} methods are default methods on
 * {@link HeightLimitView} inherited by {@link World} — they could not be
 * found when targeting {@code World.class} because Mixin does not resolve
 * inherited interface default methods through the class hierarchy.
 */
@Mixin(HeightLimitView.class)
public interface MixinHeightLimitView {

    /**
     * {@code isOutOfHeightLimit(BlockPos)} — always false for Blocky Planet.
     */
    @Inject(
        method = "isOutOfHeightLimit(Lnet/minecraft/util/math/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_isOutOfHeightLimit(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
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
    default void blockyPlanet_isOutOfHeightLimit(int y, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof World world && BlockyPlanetMod.isBlockyPlanetDimension(world)) {
            cir.setReturnValue(false);
        }
    }
}
