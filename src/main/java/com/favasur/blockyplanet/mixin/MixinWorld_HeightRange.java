package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link HeightLimitView} to override {@code getHeight()} and
 * {@code getBottomY()} for the Blocky Planet dimension.
 *
 * The vanilla overworld dimension type has {@code height = 384} and
 * {@code min_y = -64}, giving a range of -64 to 320.  Our planet surface
 * is at Y ≈ planetRadius (e.g. 7,015 for a 14 km planet), far outside
 * this range.  Vanilla feature placement, lighting, and other systems
 * use the world's HeightLimitView to clamp Y positions — they reject
 * positions outside the range, which causes "Empty height range" warnings
 * and prevents chunks from reaching the DONE status, freezing world creation.
 *
 * By overriding getHeight() and getBottomY() to cover the full planet
 * diameter, all game systems see a valid height range that includes the
 * planet surface.  The sparse section array in
 * {@link com.favasur.blockyplanet.mixin.MixinWorldChunk_CubicWorld}
 * prevents excessive memory allocation.
 */
@Mixin(HeightLimitView.class)
public interface MixinWorld_HeightRange {

    /**
     * Override {@code getHeight()} to return the full planet diameter.
     * Vanilla returns 384 (logicalHeight from overworld dimension type),
     * which is far too small for our planet surface at Y≈7,015.
     */
    @Inject(
        method = "getHeight",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_getHeight(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof World world)) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;
        double planetR = QuadSphere.planetRadius();
        cir.setReturnValue((int) (planetR * 2 + 256));
    }

    /**
     * Override {@code getBottomY()} to place the world bottom below the
     * planet core.  Vanilla returns -64 (overworld dimension type min_y).
     */
    @Inject(
        method = "getBottomY",
        at = @At("HEAD"),
        cancellable = true
    )
    default void blockyPlanet_getBottomY(CallbackInfoReturnable<Integer> cir) {
        if (!((Object) this instanceof World world)) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;
        double planetR = QuadSphere.planetRadius();
        cir.setReturnValue(-(int) planetR - 128);
    }
}
