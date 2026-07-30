package com.favasur.blockyplanet.mixin.client;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.google.common.collect.ImmutableMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.ChunkLoadingDebugRenderer;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Mixin into {@link ChunkLoadingDebugRenderer} to show chunk loading
 * data in a CIRCULAR pattern instead of the default square grid
 * when the player is in a Blocky Planet dimension.
 *
 * The mixin replaces the entire render method. It collects and
 * renders chunk loading status only for chunks within a circular
 * radius of the player, matching the spherical planet theme.
 */
@Mixin(ChunkLoadingDebugRenderer.class)
public abstract class ChunkLoadingDebugRendererMixin {

    @Shadow @Final private MinecraftClient client;

    @Shadow private double lastUpdateTime;

    /** We store our own loading data as an Object to avoid referencing the private inner class type. */
    @Unique
    private Object blockyPlanet_loadingData;

    @Unique
    private static final int RADIUS = 12;

    @Unique
    private static final int RADIUS_SQ = RADIUS * RADIUS;

    /**
     * Replace the render method for Blocky Planet dimensions to show
     * a circular chunk loading grid.
     */
    @Inject(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;DDD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_render(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                      double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        if (this.client.world == null) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(this.client.world)) return;

        // Cancel vanilla rendering — we draw a circular indicator instead
        ci.cancel();

        // Update loading data every 3 seconds
        long now = System.nanoTime();
        if (now - this.lastUpdateTime > 3.0e9) {
            this.lastUpdateTime = now;
            IntegratedServer server = this.client.getServer();
            if (server != null) {
                this.blockyPlanet_loadingData = collectCircularData(cameraX, cameraZ);
            } else {
                this.blockyPlanet_loadingData = null;
            }
        }

        if (!(this.blockyPlanet_loadingData instanceof CircularData data)) return;

        Map<ChunkPos, String> serverStates = null;
        double heightOffset = cameraY * 0.85;

        for (Map.Entry<ChunkPos, String> entry : data.clientStates.entrySet()) {
            ChunkPos pos = entry.getKey();
            String status = entry.getValue();

            // Circular distance check — skip chunks outside the circle
            int dx = pos.x - ChunkSectionPos.getSectionCoord(cameraX);
            int dz = pos.z - ChunkSectionPos.getSectionCoord(cameraZ);
            if (dx * dx + dz * dz > RADIUS_SQ) continue;

            if (serverStates != null) {
                String serverState = serverStates.get(pos);
                if (serverState != null) {
                    status = status + serverState;
                }
            }

            String[] lines = status.split("\n");
            int lineIndex = 0;
            for (String line : lines) {
                DebugRenderer.drawString(
                    matrices, vertexConsumers, line,
                    ChunkSectionPos.getOffsetPos(pos.x, 8),
                    heightOffset + lineIndex,
                    ChunkSectionPos.getOffsetPos(pos.z, 8),
                    -1, 0.15f, true, 0.0f, true
                );
                lineIndex -= 2;
            }
        }
    }

    /**
     * Collect chunk loading data in a circular pattern.
     * Only collects client-side data (server data requires private API access).
     */
    @Unique
    private CircularData collectCircularData(double cameraX, double cameraZ) {
        int centerX = ChunkSectionPos.getSectionCoord(cameraX);
        int centerZ = ChunkSectionPos.getSectionCoord(cameraZ);

        ImmutableMap.Builder<ChunkPos, String> clientBuilder = ImmutableMap.builder();
        var chunkManager = this.client.world.getChunkManager();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS_SQ) continue;

                int x = centerX + dx;
                int z = centerZ + dz;
                ChunkPos pos = new ChunkPos(x, z);
                StringBuilder sb = new StringBuilder();

                WorldChunk chunk = chunkManager.getWorldChunk(x, z, false);
                if (chunk == null) {
                    sb.append(" unloaded");
                } else if (chunk.isEmpty()) {
                    sb.append(" E");
                }
                sb.append("\n");

                clientBuilder.put(pos, sb.toString());
            }
        }

        return new CircularData(clientBuilder.build());
    }

    /**
     * Simple data holder for client chunk loading states.
     */
    @Unique
    private record CircularData(Map<ChunkPos, String> clientStates) {}
}
