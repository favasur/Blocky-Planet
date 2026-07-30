package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin into {@link Level} to override build-height bounds
 * for the Blocky Planet dimension (NeoForge / Mojmap).
 *
 * Uses {@code @Overwrite} because {@code getMinBuildHeight} and
 * {@code getMaxBuildHeight} are abstract interface methods declared
 * on {@code LevelHeightAccessor}. Mixin's {@code @Inject} cannot
 * reliably target inherited interface methods on the concrete class.
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
@Mixin(Level.class)
public abstract class MixinWorld_HeightRange {

    /**
     * @reason Override getMinBuildHeight() to place world bottom
     * below the planet core. Vanilla returns -64.
     */
    @Overwrite
    public int getMinBuildHeight() {
        Level self = (Level) (Object) this;
        if (BlockyPlanetMod.isBlockyPlanetDimension(self)) {
            return -(int) QuadSphere.planetRadius() - 128;
        }
        return self.dimensionType().minY();
    }

    /**
     * @reason Override getMaxBuildHeight() to place world top above
     * the planet surface. Vanilla returns 320 (minY + logicalHeight).
     */
    @Overwrite
    public int getMaxBuildHeight() {
        Level self = (Level) (Object) this;
        if (BlockyPlanetMod.isBlockyPlanetDimension(self)) {
            return (int) QuadSphere.planetRadius() + 128;
        }
        return self.dimensionType().minY() + self.dimensionType().logicalHeight();
    }
}
