package com.favasur.blockyplanet.mixin.client;

import com.favasur.blockyplanet.mixin.StoringChunkProgressListenerAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link LevelLoadingScreen#renderChunks} to render the
 * chunk generation progress grid as a CIRCLE instead of a square.
 *
 * All cells are drawn in the full square area (same as vanilla).
 * A smooth circular mask (Math.sqrt horizontal fills) is overlaid
 * on the four corners to create a truly round circle.
 * Size is reduced to half of vanilla's full extent.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    @Inject(
        method = "renderChunks(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/server/level/progress/StoringChunkProgressListener;IIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void blockyPlanet_renderChunks(GuiGraphics guiGraphics,
                                                    StoringChunkProgressListener progressListener,
                                                    int centerX, int centerY,
                                                    int pixelSize, int centerSizeDiv,
                                                    CallbackInfo ci) {
        if (pixelSize == 0) return; // Border rendering — skip

        ci.cancel();

        int fullDiameter = progressListener.getFullDiameter();
        int stepSize = progressListener.getDiameter();

        // Use half the display size
        int displaySize = fullDiameter / 2;
        int displayOffset = (fullDiameter - displaySize) / 2;
        int visualFull = displaySize * stepSize;

        int xStart = centerX - visualFull / 2;
        int yStart = centerY - visualFull / 2;
        int xEnd = xStart + visualFull;
        int yEnd = yStart + visualFull;

        // Get chunk statuses map
        Long2ObjectOpenHashMap<ChunkStatus> statuses =
            ((StoringChunkProgressListenerAccessor) progressListener).getStatuses();

        // 1. Draw colored cells in the FULL square area (no circular clip)
        int cellSize = pixelSize + centerSizeDiv; // 2px (matches vanilla)
        for (int i = 0; i < displaySize; i++) {
            for (int j = 0; j < displaySize; j++) {
                ChunkStatus status = statuses.get(
                    ChunkPos.asLong(i + displayOffset, j + displayOffset));
                int cellX = xStart + i * stepSize;
                int cellY = yStart + j * stepSize;
                guiGraphics.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                                 getStatusColor(status));
            }
        }

        // 2. Smooth circular mask — hides cells outside the circle
        int r = visualFull / 2;
        int maskColor = 0xFF000000; // fully opaque black
        for (int px = -r; px <= r; px++) {
            int limit = (int) Math.round(Math.sqrt(r * r - px * px));
            // Top corner
            guiGraphics.fill(centerX + px, yStart, centerX + px + 1, centerY - limit, maskColor);
            // Bottom corner
            guiGraphics.fill(centerX + px, centerY + limit + 1, centerX + px + 1, yEnd, maskColor);
        }
    }

    @Unique
    private static int getStatusColor(ChunkStatus status) {
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
