package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link World} to override {@code getHeight()} and
 * {@code getBottomY()} for the Blocky Planet dimension.
 *
 * Uses {@code @Inject(cancellable=true)} rather than {@code @Overwrite}
 * because the vanilla fallback logic is preserved when we don't cancel.
 * The compile-time warnings from Mixin's annotation processor are benign —
 * at runtime the ASM-based method resolver finds inherited interface
 * methods implemented on the concrete class.
 *
 * The vanilla overworld dimension type has {@code height = 384} and
 * {@code min_y = -64}, giving a range of -64 to 320.  Our planet surface
 * is at Y ≈ planetRadius (e.g. 7,015 for a 14 km planet), far outside
 * this range.  Feature placement, lighting, and other systems use these
 * methods to clamp Y positions — positions outside the range cause
 * "Empty height range" warnings and prevent chunks from completing.
 *
 * The sparse section array in MixinWorldChunk_CubicWorld prevents
 * excessive memory allocation despite the enlarged height range.
 */
@Mixin(World.class)
public abstract class MixinWorld_HeightRange {

    @Inject(
        method = "getHeight()I",
        at = @At("HEAD"),
        cancellable = true
    )
    public void blockyPlanet_getHeight(CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(self)) return;
        cir.setReturnValue((int) (QuadSphere.planetRadius() * 2 + 256));
    }

    @Inject(
        method = "getBottomY()I",
        at = @At("HEAD"),
        cancellable = true
    )
    public void blockyPlanet_getBottomY(CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(self)) return;
        cir.setReturnValue(-(int) QuadSphere.planetRadius() - 128);
    }
}
