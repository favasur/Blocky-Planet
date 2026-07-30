package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.player.CustomGravity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into PlayerEntity to apply custom radial gravity in the Blocky Planet dimension.
 *
 * Overrides vanilla gravity with gravity pointing toward the planet center.
 * The thruster activates when the player is airborne and pressing jump
 * (detected via velocity changes). Player up-vector is smoothly rotated
 * to align with the planet surface.
 */
@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {

    @Unique
    private long blockyPlanet_lastGravityTick = 0;

    @Unique
    private double blockyPlanet_prevVelocityY = 0.0;

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        long gameTime = player.getWorld().getTime();
        if (gameTime == blockyPlanet_lastGravityTick) return;
        blockyPlanet_lastGravityTick = gameTime;

        // Detect jump press: velocity Y suddenly increases (from negative/zero to positive)
        double currentVY = player.getVelocity().y;
        boolean jumpPressed = currentVY > 0.0 && blockyPlanet_prevVelocityY <= 0.0;
        blockyPlanet_prevVelocityY = currentVY;

        CustomGravity.applyGravity(player, jumpPressed);
    }
}
