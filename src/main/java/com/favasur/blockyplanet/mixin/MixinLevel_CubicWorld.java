package com.favasur.blockyplanet.mixin;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link World} to remove all world boundaries for the Blocky
 * Planet dimension:
 *
 * - {@code getBlockState/setBlockState} route through the unbounded
 *   {@link PlanetBlockStorage} instead of vanilla height-bounded chunks.
 * - {@code isOutOfHeightLimit} always returns false — any Y is valid.
 * - The world border is bypassed by registering this World's border
 *   in a global set checked by {@link MixinWorldBorder_CubicWorld}.
 */
@Mixin(World.class)
public abstract class MixinLevel_CubicWorld {

    @Shadow private WorldBorder border;

    @Unique
    private boolean blockyPlanet_checked = false;

    @Unique
    private boolean blockyPlanet_isCubic = false;

    @Unique
    private void blockyPlanet_ensureInit() {
        if (!blockyPlanet_checked) {
            blockyPlanet_checked = true;
            World self = (World) (Object) this;
            blockyPlanet_isCubic = BlockyPlanetMod.isBlockyPlanetDimension(self);
            if (blockyPlanet_isCubic) {
                BlockyPlanetMod.getOrCreateStorage(self);
                // Register this world's border so MixinWorldBorder_CubicWorld
                // can bypass it without affecting other dimensions.
                BlockyPlanetMod.BLOCKY_BORDERS.add(this.border);
                BlockyPlanetMod.LOGGER.info(
                    "Cubic world mixin active for dimension {}",
                    self.getRegistryKey().getValue());
            }
        }
    }

    // ─── Unbounded block access ─────────────────────────────────────────────

    @Inject(
        method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        blockyPlanet_ensureInit();
        if (!blockyPlanet_isCubic) return;

        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage((World) (Object) this);
        cir.setReturnValue(storage.getBlockState(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(
        method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                             CallbackInfoReturnable<Boolean> cir) {
        blockyPlanet_ensureInit();
        if (!blockyPlanet_isCubic) return;

        PlanetBlockStorage storage = BlockyPlanetMod.getOrCreateStorage((World) (Object) this);
        storage.setBlockState(pos.getX(), pos.getY(), pos.getZ(), state);
        cir.setReturnValue(true);
    }

}
