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
 * when generating a Blocky Planet world (NeoForge/Mojmap).
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

    /**
     * Replace renderChunks with a circular version.
     */
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
        if (pixelSize == 0) return; // Border rendering — keep vanilla

        ci.cancel();

        int diameter = progressListener.getDiameter();
        int fullDiameter = progressListener.getFullDiameter();
        int cellSize = pixelSize * 2 + 1;
        int visualFull = fullDiameter * cellSize;

        int xStart = centerX - visualFull / 2;
        int yStart = centerY - visualFull / 2;
        float radiusSq = (fullDiameter / 2.0f) * (fullDiameter / 2.0f);
        float halfFull = fullDiameter / 2.0f;

        // Access the private statuses map via accessor mixin
        Long2ObjectOpenHashMap<ChunkStatus> statuses =
            ((StoringChunkProgressListenerAccessor) progressListener).getStatuses();

        // Draw border (same as vanilla)
        guiGraphics.fill(centerX - visualFull / 2 - 1, centerY - visualFull / 2 - 1,
                         centerX + visualFull / 2 + 1, centerY - visualFull / 2,
                         0xFF2222F0);
        guiGraphics.fill(centerX - visualFull / 2 - 1, centerY + visualFull / 2,
                         centerX + visualFull / 2 + 1, centerY + visualFull / 2 + 1,
                         0xFF2222F0);
        guiGraphics.fill(centerX - visualFull / 2 - 1, centerY - visualFull / 2,
                         centerX - visualFull / 2, centerY + visualFull / 2,
                         0xFF2222F0);
        guiGraphics.fill(centerX + visualFull / 2, centerY - visualFull / 2,
                         centerX + visualFull / 2 + 1, centerY + visualFull / 2,
                         0xFF2222F0);

        // Draw chunk progress cells — only within the circular radius
        for (int i = 0; i < fullDiameter; i++) {
            for (int j = 0; j < fullDiameter; j++) {
                float dx = i - halfFull + 0.5f;
                float dz = j - halfFull + 0.5f;
                if (dx * dx + dz * dz > radiusSq) continue;

                ChunkStatus status = statuses.get(ChunkPos.asLong(i, j));
                int cellX = xStart + i * cellSize;
                int cellY = yStart + j * cellSize;
                int color = getStatusColor(status) | 0xFF000000;
                guiGraphics.fill(cellX, cellY,
                                 cellX + cellSize, cellY + cellSize,
                                 color);
            }
        }
    }

    /**
     * Map ChunkStatus to its display color (matching vanilla's STATUS_TO_COLOR map).
     */
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
