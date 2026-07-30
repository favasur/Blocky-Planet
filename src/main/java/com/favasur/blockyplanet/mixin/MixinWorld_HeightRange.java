package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin into {@link World} to override {@code getHeight()} and
 * {@code getBottomY()} for the Blocky Planet dimension.
 *
 * Uses {@code @Overwrite} because:
 * - {@code @Inject} with explicit descriptors fails at runtime: the
 *   refmap maps the method to its obfuscated name on the
 *   {@code HeightLimitView} interface, not on {@code World}. When Mixin
 *   then searches {@code World}'s bytecode using the interface's
 *   obfuscated name, it finds nothing → {@code InvalidInjectionException}.
 * - {@code @Overwrite} bypasses the refmap entirely, directly replacing
 *   the method body in the target class via ASM.
 *
 * The vanilla overworld dimension type has {@code height = 384} and
 * {@code min_y = -64}. Our planet surface is at Y ≈ planetRadius
 * (e.g. 7,015 for a 14 km planet). Feature placement uses these methods
 * to clamp Y positions — wrong values cause "Empty height range" stalls.
 *
 * For non-BlockyPlanet worlds, dimension-key-based fallbacks preserve
 * the vanilla height ranges for Nether (256 blocks) and End (256 blocks).
 */
@Mixin(World.class)
public abstract class MixinWorld_HeightRange {

    /**
     * @reason Direct bytecode replacement avoids refmap resolution issues
     * with inherited interface methods.
     */
    @Overwrite
    public int getHeight() {
        World self = (World) (Object) this;
        if (BlockyPlanetMod.isBlockyPlanetDimension(self)) {
            return (int) (QuadSphere.planetRadius() * 2 + 256);
        }
        Identifier id = self.getRegistryKey().getValue();
        String p = id.getPath();
        if ("the_nether".equals(p) || "the_end".equals(p)) return 256;
        return 384;
    }

    /**
     * @reason Direct bytecode replacement avoids refmap resolution issues
     * with inherited interface methods.
     */
    @Overwrite
    public int getBottomY() {
        World self = (World) (Object) this;
        if (BlockyPlanetMod.isBlockyPlanetDimension(self)) {
            return -(int) QuadSphere.planetRadius() - 128;
        }
        Identifier id = self.getRegistryKey().getValue();
        String p = id.getPath();
        if ("the_nether".equals(p) || "the_end".equals(p)) return 0;
        return -64;
    }
}
