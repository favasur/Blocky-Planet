package com.favasur.blockyplanet.mixin.client;

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
 * chunk generation progress grid as a CIRCLE instead of a square.
 *
 * All cells are drawn in the full square area (same as vanilla).
 * A smooth circular mask (drawn via Math.sqrt horizontal fills) is
 * overlaid on the four corners to create a clean, truly round circle.
 *
 * Size is reduced to half of vanilla's full extent so the circle
 * is more compact and cleaner-looking.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

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
        if (pixelSize == 0) return; // Border rendering — skip

        ci.cancel();

        int stepSize = progressProvider.getCenterSize();
        int fullSize = progressProvider.getSize();

        // Use half the display size so the circle is compact
        int displaySize = fullSize / 2;
        int displayOffset = (fullSize - displaySize) / 2;
        int visualFull = displaySize * stepSize;

        int xStart = centerX - visualFull / 2;
        int yStart = centerY - visualFull / 2;
        int xEnd = xStart + visualFull;
        int yEnd = yStart + visualFull;

        // 1. Draw colored cells in the FULL square area (no circular clip)
        //    cellSize = pixelSize + centerSizeDiv = 2 (matches vanilla)
        int cellSize = pixelSize + centerSizeDiv;
        for (int i = 0; i < displaySize; i++) {
            for (int j = 0; j < displaySize; j++) {
                ChunkStatus status = progressProvider.getChunkStatus(
                    i + displayOffset, j + displayOffset);
                int cellX = xStart + i * stepSize;
                int cellY = yStart + j * stepSize;
                context.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                             getStatusColor(status));
            }
        }

        // 2. Smooth circular mask — hides cells outside the circle
        //    Using Math.sqrt gives a clean mathematically-perfect circle edge,
        //    unlike clipping square cells which creates jagged "pixel-round" edges.
        int r = visualFull / 2;
        int maskColor = 0xFF000000; // fully opaque black — matches screen bg
        for (int px = -r; px <= r; px++) {
            int limit = (int) Math.round(Math.sqrt(r * r - px * px));

            // Top corner: fill from yStart up to the circle's top edge
            context.fill(centerX + px, yStart, centerX + px + 1, centerY - limit, maskColor);
            // Bottom corner: fill from the circle's bottom edge to yEnd
            context.fill(centerX + px, centerY + limit + 1, centerX + px + 1, yEnd, maskColor);
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
