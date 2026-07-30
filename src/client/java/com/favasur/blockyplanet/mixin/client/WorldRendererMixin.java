package com.favasur.blockyplanet.mixin.client;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link WorldRenderer} to replace the vanilla sky with a
 * space skybox when the player is in a Blocky Planet dimension.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Shadow @Final private MinecraftClient client;

    /**
     * Override renderSky to draw a space skybox instead of the vanilla sky.
     */
    @Inject(
        method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;ZLjava/lang/Runnable;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void blockyPlanet_renderSky(Matrix4f matrix4f, Matrix4f projMatrix, float tickDelta,
                                          Camera camera, boolean bl, Runnable runnable, CallbackInfo ci) {
        ClientWorld world = this.client.world;
        if (world == null) return;
        if (!BlockyPlanetMod.isBlockyPlanetDimension(world)) return;

        // Cancel vanilla sky rendering
        ci.cancel();

        double pr = QuadSphere.planetRadius();
        Tessellator tessellator = Tessellator.getInstance();

        // -- Solid pitch-black background sphere --
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionProgram);

        BufferBuilder buf = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        float R = 100.0f;
        for (int ring = 0; ring < 16; ring++) {
            float t1 = (float) (ring * Math.PI / 16);
            float t2 = (float) ((ring + 1) * Math.PI / 16);
            for (int seg = 0; seg < 32; seg++) {
                float p1 = (float) (seg * 2 * Math.PI / 32);
                float p2 = (float) ((seg + 1) * 2 * Math.PI / 32);

                buf.vertex(matrix4f, R * (float)Math.sin(t1) * (float)Math.cos(p1), R * (float)Math.cos(t1), R * (float)Math.sin(t1) * (float)Math.sin(p1));
                buf.vertex(matrix4f, R * (float)Math.sin(t2) * (float)Math.cos(p1), R * (float)Math.cos(t2), R * (float)Math.sin(t2) * (float)Math.sin(p1));
                buf.vertex(matrix4f, R * (float)Math.sin(t2) * (float)Math.cos(p2), R * (float)Math.cos(t2), R * (float)Math.sin(t2) * (float)Math.sin(p2));
                buf.vertex(matrix4f, R * (float)Math.sin(t1) * (float)Math.cos(p2), R * (float)Math.cos(t1), R * (float)Math.sin(t1) * (float)Math.sin(p2));
            }
        }
        RenderSystem.setShaderColor(0.01f, 0.01f, 0.03f, 1.0f);
        BufferRenderer.drawWithGlobalProgram(buf.end());

        // -- Star field using POSITION_COLOR (each star has individual brightness) --
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder starBuf = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        long seed = world.getTime() ^ 0xDEADBEEFL;
        java.util.Random starRand = new java.util.Random(seed);

        for (int i = 0; i < 2000; i++) {
            double theta = starRand.nextDouble() * Math.PI;
            double phi = starRand.nextDouble() * 2 * Math.PI;
            if (Math.cos(theta) < -0.1) continue; // Skip below horizon

            float d = 95.0f + starRand.nextFloat() * 5.0f;
            float size = 0.1f + starRand.nextFloat() * 0.3f;
            float br = 0.5f + starRand.nextFloat() * 0.5f;

            float sx = d * (float)(Math.sin(theta) * Math.cos(phi));
            float sy = d * (float)Math.cos(theta);
            float sz = d * (float)(Math.sin(theta) * Math.sin(phi));
            float hs = size * 0.5f;

            starBuf.vertex(matrix4f, sx - hs, sy - hs, sz - hs).color(br, br, br * 0.9f, 1.0f);
            starBuf.vertex(matrix4f, sx + hs, sy - hs, sz - hs).color(br, br, br * 0.9f, 1.0f);
            starBuf.vertex(matrix4f, sx + hs, sy + hs, sz - hs).color(br, br, br * 0.9f, 1.0f);
            starBuf.vertex(matrix4f, sx - hs, sy + hs, sz - hs).color(br, br, br * 0.9f, 1.0f);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        BufferRenderer.drawWithGlobalProgram(starBuf.end());

        // -- Horizon glow band at the planet's curvature angle --
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder hBuf = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        float dipAngle = (float) Math.acos(pr / (pr + 1.62));
        float horizonTheta = (float) (Math.PI / 2 - dipAngle);
        int segments = 64;

        for (int i = 0; i < segments; i++) {
            float a1 = (float) (i * 2 * Math.PI / segments);
            float a2 = (float) ((i + 1) * 2 * Math.PI / segments);

            float hx1 = R * (float)(Math.sin(horizonTheta) * Math.cos(a1));
            float hy1 = R * (float)Math.cos(horizonTheta);
            float hz1 = R * (float)(Math.sin(horizonTheta) * Math.sin(a1));

            float hx2 = R * (float)(Math.sin(horizonTheta) * Math.cos(a2));
            float hy2 = R * (float)Math.cos(horizonTheta);
            float hz2 = R * (float)(Math.sin(horizonTheta) * Math.sin(a2));

            int alpha = (int) (60 * (1.0f - Math.abs(horizonTheta - (float) Math.PI / 2) * 2));
            alpha = Math.min(alpha, 80);
            if (alpha < 0) alpha = 0;
            float a = alpha / 255.0f;

            hBuf.vertex(matrix4f, hx1, hy1, hz1).color(0.6f, 0.7f, 1.0f, a);
            hBuf.vertex(matrix4f, hx2, hy2, hz2).color(0.6f, 0.7f, 1.0f, a);
            hBuf.vertex(matrix4f, hx2 * 0.98f, hy2 * 0.98f, hz2 * 0.98f).color(0.6f, 0.7f, 1.0f, 0);
            hBuf.vertex(matrix4f, hx1 * 0.98f, hy1 * 0.98f, hz1 * 0.98f).color(0.6f, 0.7f, 1.0f, 0);
        }
        BufferRenderer.drawWithGlobalProgram(hBuf.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
    }
}
