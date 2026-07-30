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
 * Cells are drawn large enough to fill the circle continuously
 * (cellSize = stepSize), creating a smooth, solid-looking circle
 * with colored region indicators instead of sparse 2px dots.
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
        int visualFull = fullSize * stepSize;

        int xStart = centerX - visualFull / 2;
        int yStart = centerY - visualFull / 2;
        int xEnd = xStart + visualFull;
        int yEnd = yStart + visualFull;

        int radius = fullSize / 2;
        int radiusSq = radius * radius;

        // 1. Solid dark background for the full square area
        context.fill(xStart, yStart, xEnd, yEnd, 0x4F000000);

        // 2. Draw cells filling the circle area (cellSize = stepSize = no gaps)
        //    Full-size cells create a smooth solid-looking circle instead of
        //    scattered 2px dots with 13px gaps.
        int cellSize = stepSize;
        for (int i = 0; i < fullSize; i++) {
            for (int j = 0; j < fullSize; j++) {
                int dx = i - radius;
                int dz = j - radius;
                if (dx * dx + dz * dz > radiusSq) continue;

                ChunkStatus status = progressProvider.getChunkStatus(i, j);
                int cellX = xStart + i * stepSize;
                int cellY = yStart + j * stepSize;
                context.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                             getStatusColor(status));
            }
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
