package com.favasur.blockyplanet.mixin.client;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.ChunkDebugRenderer;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Mixin into {@link ChunkDebugRenderer} to show chunk loading data
 * in a CIRCULAR pattern instead of the default square grid
 * when the player is in a Blocky Planet dimension (NeoForge/Mojmap).
 */
@Mixin(ChunkDebugRenderer.class)
public abstract class ChunkDebugRendererMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow @Final private int radius;

    @Shadow private double lastUpdateTime;

    @Unique
    private Object blockyPlanet_data;

    @Unique
    private static final int RADIUS_SQ = 12 * 12;

    /**
     * Replace render method for Blocky Planet dimensions with circular rendering.
     */
    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;DDD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_render(PoseStack poseStack, MultiBufferSource bufferSource,
                                      double camX, double camY, double camZ, CallbackInfo ci) {
        ClientLevel world = this.minecraft.level;
        if (world == null) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        ci.cancel();

        double now = (double) System.nanoTime();
        if (now - this.lastUpdateTime > 3.0E9) {
            this.lastUpdateTime = now;
            this.blockyPlanet_data = buildCircularData(world, camX, camZ);
        }

        if (!(this.blockyPlanet_data instanceof CircularChunkData data)) return;

        double heightOffset = this.minecraft.gameRenderer.getMainCamera().getPosition().y * 0.85;

        for (Map.Entry<ChunkPos, String> entry : data.clientData.entrySet()) {
            ChunkPos pos = entry.getKey();
            String status = entry.getValue();

            int dx = pos.x - SectionPos.posToSectionCoord(camX);
            int dz = pos.z - SectionPos.posToSectionCoord(camZ);
            if (dx * dx + dz * dz > RADIUS_SQ) continue;

            String[] lines = status.split("\n");
            int lineIndex = 0;
            for (String line : lines) {
                DebugRenderer.renderFloatingText(
                    poseStack, bufferSource, line,
                    SectionPos.sectionToBlockCoord(pos.x, 8),
                    heightOffset + lineIndex,
                    SectionPos.sectionToBlockCoord(pos.z, 8),
                    -1, 0.15F, true, 0.0F, true
                );
                lineIndex -= 2;
            }
        }
    }

    /**
     * Build chunk loading data in a circular pattern.
     */
    @Unique
    private CircularChunkData buildCircularData(ClientLevel world, double camX, double camZ) {
        int centerX = SectionPos.posToSectionCoord(camX);
        int centerZ = SectionPos.posToSectionCoord(camZ);

        ImmutableMap.Builder<ChunkPos, String> builder = ImmutableMap.builder();
        ClientChunkCache chunkCache = world.getChunkSource();

        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                if (dx * dx + dz * dz > RADIUS_SQ) continue;

                int x = centerX + dx;
                int z = centerZ + dz;
                ChunkPos pos = new ChunkPos(x, z);
                StringBuilder sb = new StringBuilder();

                LevelChunk chunk = chunkCache.getChunk(x, z, false);
                if (chunk == null) {
                    sb.append(" unloaded");
                } else if (chunk.isEmpty()) {
                    sb.append(" E");
                }
                sb.append("\n");

                builder.put(pos, sb.toString());
            }
        }

        return new CircularChunkData(builder.build());
    }

    @Unique
    private record CircularChunkData(Map<ChunkPos, String> clientData) {}
}
