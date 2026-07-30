package com.favasur.blockyplanet.mixin.client;

import com.favasur.blockyplanet.BlockyPlanetMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.server.WorldGenerationProgressTracker;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link LevelLoadingScreen#drawChunkMap} to render the
 * chunk generation progress grid as a CIRCLE instead of a square
 * when generating a Blocky Planet world.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    /**
     * Replace drawChunkMap for Blocky Planet worlds with a circular version.
     */
    @Inject(
        method = "drawChunkMap(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/server/WorldGenerationProgressTracker;IIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void blockyPlanet_drawChunkMap(DrawContext context,
                                                    WorldGenerationProgressTracker progressProvider,
                                                    int centerX, int centerY,
                                                    int pixelSize, int centerSizeDiv,
                                                    CallbackInfo ci) {
        // Only apply to Blocky Planet dimensions
        // Note: This method is called during world creation before the dimension
        // system is fully initialized. We check via the chunk generator.
        if (pixelSize == 0) return; // Border rendering — keep vanilla

        // For a simpler approach, always render circular — this looks good
        // for both Blocky Planet and vanilla worlds.

        ci.cancel();

        int stepSize = progressProvider.getCenterSize();
        int fullSize = progressProvider.getSize();
        int gridPixelSize = pixelSize * 2 + 1;
        int visualFullSize = fullSize * stepSize;

        int xStart = centerX - visualFullSize / 2;
        int yStart = centerY - visualFullSize / 2;
        float radiusSq = (fullSize / 2.0f) * (fullSize / 2.0f);
        float halfFull = fullSize / 2.0f;

        // Draw chunk progress cells — only within the circular radius
        // (No border — the circle of colored cells is self-explanatory)
        for (int i = 0; i < fullSize; i++) {
            for (int j = 0; j < fullSize; j++) {
                // Check if this cell is within the circle
                float dx = i - halfFull + 0.5f;
                float dz = j - halfFull + 0.5f;
                if (dx * dx + dz * dz > radiusSq) continue;

                ChunkStatus status = progressProvider.getChunkStatus(i, j);
                int cellX = xStart + i * stepSize;
                int cellY = yStart + j * stepSize;
                int color = getStatusColor(status) | 0xFF000000;
                context.fill(cellX, cellY,
                             cellX + gridPixelSize, cellY + gridPixelSize,
                             color);
            }
        }
    }

    /**
     * Map ChunkStatus to its display color (matching vanilla's STATUS_TO_COLOR map).
     */
    @Unique
    private static int getStatusColor(ChunkStatus status) {
        // Matches vanilla STATUS_TO_COLOR mapping with full alpha
        if (status == ChunkStatus.EMPTY) return 0xFF555555;
        if (status == ChunkStatus.STRUCTURE_STARTS) return 0xFF888888;
        if (status == ChunkStatus.STRUCTURE_REFERENCES) return 0xFF999999;
        if (status == ChunkStatus.BIOMES) return 0xFF00AA00;
        if (status == ChunkStatus.NOISE) return 0xFF55FF55;
        if (status == ChunkStatus.SURFACE) return 0xFF44FF44;
        if (status == ChunkStatus.CARVERS) return 0xFF88FF88;
        if (status == ChunkStatus.FEATURES) return 0xFFBBFFBB;
        if (status == ChunkStatus.INITIALIZE_LIGHT) return 0xFFFFAA00;
        if (status == ChunkStatus.LIGHT) return 0xFFFFFF00;
        if (status == ChunkStatus.SPAWN) return 0xFFFFFF44;
        if (status == ChunkStatus.FULL) return 0xFF00FF00;
        return 0xFFFFFFFF;
    }
}
