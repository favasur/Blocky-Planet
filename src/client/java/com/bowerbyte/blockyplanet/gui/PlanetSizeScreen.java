package com.bowerbyte.blockyplanet.gui;

import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Configuration screen for planet diameter — opened from the Create World screen.
 * Uses a logarithmic slider spanning [500 .. 129_000_000] blocks diameter.
 */
public class PlanetSizeScreen extends Screen {

    private final Screen parent;

    /** Callback invoked when the player confirms. */
    public interface DoneCallback {
        void onDone(int selectedDiameter);
    }

    private final DoneCallback onDone;
    private CustomSlider diameterSlider;

    /** The diameter when this screen was opened — used by Cancel to revert. */
    private final int originalDiameter;

    public PlanetSizeScreen(Screen parent, DoneCallback onDone) {
        super(Text.literal("Blocky Planet — Planet Size"));
        this.parent = parent;
        this.onDone = onDone;
        this.originalDiameter = BlockyPlanetConfig.getPlanetDiameter();
    }

    @Override
    protected void init() {
        super.init();

        int cx = width / 2;
        int sw = 310;                // slider width
        int sl = cx - sw / 2;        // slider left edge

        // ─── Logarithmic Slider ──────────────────────────────────────────
        double initial = BlockyPlanetConfig.diameterToSlider(BlockyPlanetConfig.getPlanetDiameter());

        diameterSlider = new CustomSlider(sl, 65, sw, 20, initial);

        addDrawableChild(diameterSlider);

        // ─── Preset buttons ──────────────────────────────────────────────
        int py = 100;
        int pw = 70;
        int gap = 6;
        int tw = pw * 5 + gap * 4;
        int sx = cx - tw / 2;

        addDrawableChild(makePreset("§7Asteroid", 500,         sx, py, pw));
        addDrawableChild(makePreset("§eSmall",     2_000,       sx + (pw + gap), py, pw));
        addDrawableChild(makePreset("§aEarth",     12_742_000,  sx + (pw + gap) * 2, py, pw));
        addDrawableChild(makePreset("§3Large",     100_000_000, sx + (pw + gap) * 3, py, pw));
        addDrawableChild(makePreset("§cMax",       129_000_000, sx + (pw + gap) * 4, py, pw));

        // ─── Done / Cancel ───────────────────────────────────────────────
        addDrawableChild(ButtonWidget.builder(
            Text.literal("Done"),
            b -> done()
        ).dimensions(cx - 110, height - 40, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(
            Text.literal("Cancel"),
            b -> {
                // Revert to the diameter that was set when this screen opened
                BlockyPlanetConfig.setPlanetDiameter(originalDiameter);
                close();
            }
        ).dimensions(cx + 10, height - 40, 100, 20).build());
    }

    private ButtonWidget makePreset(String label, int diameter, int x, int y, int w) {
        return ButtonWidget.builder(
            Text.literal(label),
            b -> setDiameter(diameter)
        ).dimensions(x, y, w, 20).build();
    }

    private void setDiameter(int d) {
        BlockyPlanetConfig.setPlanetDiameter(d);
        if (diameterSlider != null) {
            diameterSlider.setValuePublic(BlockyPlanetConfig.diameterToSlider(d));
        }
    }

    private void done() {
        if (onDone != null) {
            onDone.onDone(BlockyPlanetConfig.getPlanetDiameter());
        }
        close();
    }

    // ─── Custom slider that exposes setValue ──────────────────────────────

    private static class CustomSlider extends SliderWidget {
        CustomSlider(int x, int y, int w, int h, double val) {
            super(x, y, w, h, Text.empty(), val);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int d = BlockyPlanetConfig.sliderToDiameter(value);
            setMessage(Text.literal("⌀ " + BlockyPlanetConfig.formatDiameter(d)));
        }

        @Override
        protected void applyValue() {
            int d = BlockyPlanetConfig.sliderToDiameter(value);
            BlockyPlanetConfig.setPlanetDiameter(d);
        }

        /** Expose the protected setValue publicly — needed because SliderWidget.setValue is private. */
        public void setValuePublic(double val) {
            value = val;
            applyValue();
            updateMessage();
        }
    }

    // ─── Render ──────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        int cx = width / 2;

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§lPlanet Diameter"), cx, 20, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§7Adjust the spherical planet's size. Larger planets feel flatter."), cx, 35, 0xAAAAAA);

        // Current stats
        double r = BlockyPlanetConfig.getPlanetRadius();
        double dip = BlockyPlanetConfig.currentHorizonDip();
        double hd = BlockyPlanetConfig.currentHorizonDistance();
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(
            String.format("§7Radius: §f%s  §7|  Horizon dip: §f%.4f°  §7|  Dist: §f%s",
                BlockyPlanetConfig.formatRadius(r), dip,
                BlockyPlanetConfig.formatDiameter((int) hd))), cx, 90, 0xFFFFFF);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7Presets:"), cx, 120, 0xAAAAAA);

        // Horizon curvature indicator
        int iy = height - 100;
        String curve;
        if (dip < 0.001) {
            curve = "§7Horizon: §oessentially flat  §8(planet too large to see curvature)";
        } else if (dip < 0.01) {
            curve = String.format("§7Horizon: §fvery slight curve  (%.4f°)", dip);
        } else if (dip < 0.1) {
            curve = String.format("§7Horizon: §fgentle curve  (%.4f°)", dip);
        } else {
            curve = String.format("§7Horizon: §fpronounced curve  (%.4f°)", dip);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(curve), cx, iy, 0xFFFFFF);

        // Draw a simple visual indicator: a short horizontal line and an arc
        // (no drawCircle available, so we use a simple text/box approach)
        String sizeLabel;
        if (r < 500) sizeLabel = "§7● §8Tiny asteroid";
        else if (r < 5_000) sizeLabel = "§7● §aSmall moon";
        else if (r < 50_000) sizeLabel = "§7● §eMedium planet";
        else if (r < 500_000) sizeLabel = "§7● §6Large planet";
        else if (r < 5_000_000) sizeLabel = "§7● §cGiant planet";
        else sizeLabel = "§7● §4Supergiant (nearly flat)";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(sizeLabel), cx, iy - 14, 0xFFFFFF);
    }

    @Override
    public void close() {
        assert client != null;
        client.setScreen(parent);
    }
}
