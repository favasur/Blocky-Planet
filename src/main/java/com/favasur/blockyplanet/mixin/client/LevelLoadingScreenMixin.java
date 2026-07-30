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
 * chunk generation progress grid as a CIRCLE instead of a square
 * — same opaque visual style as vanilla, just clipped to a circle.
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

        int cellSize = pixelSize + centerSizeDiv; // 2px cells (matches vanilla)
        int fullDiameter = progressListener.getFullDiameter();
        int stepSize = progressListener.getDiameter();
        int visualFull = fullDiameter * stepSize;

        int xStart = centerX - visualFull / 2;
        int yStart = centerY - visualFull / 2;
        int xEnd = xStart + visualFull;
        int yEnd = yStart + visualFull;

        int radius = fullDiameter / 2;
        int radiusSq = radius * radius;

        // 1. Solid dark background for the full square area
        guiGraphics.fill(xStart, yStart, xEnd, yEnd, 0x4F000000);

        Long2ObjectOpenHashMap<ChunkStatus> statuses =
            ((StoringChunkProgressListenerAccessor) progressListener).getStatuses();

        // 2. Draw colored cells only within the circle
        for (int i = 0; i < fullDiameter; i++) {
            for (int j = 0; j < fullDiameter; j++) {
                int dx = i - radius;
                int dz = j - radius;
                if (dx * dx + dz * dz > radiusSq) continue;

                ChunkStatus status = statuses.get(ChunkPos.asLong(i, j));
                int cellX = xStart + i * stepSize;
                int cellY = yStart + j * stepSize;
                guiGraphics.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                                 getStatusColor(status));
            }
        }

        // 3. Draw a visible circular border (2px thick)
        int borderColor = 0xFF4444AA;
        int borderR = radius * stepSize + stepSize / 2; // edge of the grid

        // Outer ring (r+1)
        for (int px = -borderR - 1; px <= borderR + 1; px++) {
            int py = (int) Math.round(Math.sqrt((borderR + 1) * (borderR + 1) - px * px));
            int cx = centerX + px;
            int cy = centerY + py;
            if (cx >= xStart && cx < xEnd && cy >= yStart && cy < yEnd)
                guiGraphics.fill(cx, cy, cx + 1, cy + 1, borderColor);
            cy = centerY - py;
            if (cx >= xStart && cx < xEnd && cy >= yStart && cy < yEnd)
                guiGraphics.fill(cx, cy, cx + 1, cy + 1, borderColor);
        }
        // Inner ring (r)
        for (int px = -borderR; px <= borderR; px++) {
            int py = (int) Math.round(Math.sqrt(borderR * borderR - px * px));
            int cx = centerX + px;
            int cy = centerY + py;
            if (cx >= xStart && cx < xEnd && cy >= yStart && cy < yEnd)
                guiGraphics.fill(cx, cy, cx + 1, cy + 1, borderColor);
            cy = centerY - py;
            if (cx >= xStart && cx < xEnd && cy >= yStart && cy < yEnd)
                guiGraphics.fill(cx, cy, cx + 1, cy + 1, borderColor);
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
