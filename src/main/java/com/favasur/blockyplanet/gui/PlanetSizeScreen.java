package com.favasur.blockyplanet.gui;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Configuration screen for planet diameter — opened from the Create World screen.
 * Uses a logarithmic slider spanning [500 .. 129_000_000] blocks diameter.
 */
public class PlanetSizeScreen extends Screen {

    private final Screen parent;

    public interface DoneCallback {
        void onDone(int selectedDiameter);
    }

    private final DoneCallback onDone;
    private CustomSlider diameterSlider;
    private final int originalDiameter;

    public PlanetSizeScreen(Screen parent, DoneCallback onDone) {
        super(Component.literal("Blocky Planet — Planet Size"));
        this.parent = parent;
        this.onDone = onDone;
        this.originalDiameter = BlockyPlanetConfig.getPlanetDiameter();
    }

    @Override
    protected void init() {
        super.init();

        int cx = width / 2;
        int sw = 310;
        int sl = cx - sw / 2;

        double initial = BlockyPlanetConfig.diameterToSlider(BlockyPlanetConfig.getPlanetDiameter());

        diameterSlider = new CustomSlider(sl, 65, sw, 20, initial);

        addRenderableWidget(diameterSlider);

        int py = 100;
        int pw = 70;
        int gap = 6;
        int tw = pw * 5 + gap * 4;
        int sx = cx - tw / 2;

        addRenderableWidget(makePreset("§7Asteroid", 500,         sx, py, pw));
        addRenderableWidget(makePreset("§eSmall",     2_000,       sx + (pw + gap), py, pw));
        addRenderableWidget(makePreset("§aEarth",     12_742_000,  sx + (pw + gap) * 2, py, pw));
        addRenderableWidget(makePreset("§3Large",     100_000_000, sx + (pw + gap) * 3, py, pw));
        addRenderableWidget(makePreset("§cMax",       129_000_000, sx + (pw + gap) * 4, py, pw));

        addRenderableWidget(Button.builder(
            Component.literal("Done"),
            b -> done()
        ).bounds(cx - 110, height - 40, 100, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            b -> {
                BlockyPlanetConfig.setPlanetDiameter(originalDiameter);
                onClose();
            }
        ).bounds(cx + 10, height - 40, 100, 20).build());
    }

    private Button makePreset(String label, int diameter, int x, int y, int w) {
        return Button.builder(
            Component.literal(label),
            b -> setDiameter(diameter)
        ).bounds(x, y, w, 20).build();
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
        onClose();
    }

    private static class CustomSlider extends AbstractSliderButton {
        CustomSlider(int x, int y, int w, int h, double val) {
            super(x, y, w, h, Component.empty(), val);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int d = BlockyPlanetConfig.sliderToDiameter(value);
            setMessage(Component.literal("⌀ " + BlockyPlanetConfig.formatDiameter(d)));
        }

        @Override
        protected void applyValue() {
            int d = BlockyPlanetConfig.sliderToDiameter(value);
            BlockyPlanetConfig.setPlanetDiameter(d);
        }

        public void setValuePublic(double val) {
            value = val;
            applyValue();
            updateMessage();
        }
    }

    @Override
    public void render(GuiGraphics ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        int cx = width / 2;

        ctx.drawCenteredString(font, Component.literal("§lPlanet Diameter"), cx, 20, 0xFFFFFF);
        ctx.drawCenteredString(font,
            Component.literal("§7Adjust the spherical planet's size. Larger planets feel flatter."), cx, 35, 0xAAAAAA);

        double r = BlockyPlanetConfig.getPlanetRadius();
        double dip = BlockyPlanetConfig.currentHorizonDip();
        double hd = BlockyPlanetConfig.currentHorizonDistance();
        ctx.drawCenteredString(font, Component.literal(
            String.format("§7Radius: §f%s  §7|  Horizon dip: §f%.4f°  §7|  Dist: §f%s",
                BlockyPlanetConfig.formatRadius(r), dip,
                BlockyPlanetConfig.formatDiameter((int) hd))), cx, 90, 0xFFFFFF);

        ctx.drawCenteredString(font, Component.literal("§7Presets:"), cx, 120, 0xAAAAAA);

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
        ctx.drawCenteredString(font, Component.literal(curve), cx, iy, 0xFFFFFF);

        String sizeLabel;
        if (r < 500) sizeLabel = "§7● §8Tiny asteroid";
        else if (r < 5_000) sizeLabel = "§7● §aSmall moon";
        else if (r < 50_000) sizeLabel = "§7● §eMedium planet";
        else if (r < 500_000) sizeLabel = "§7● §6Large planet";
        else if (r < 5_000_000) sizeLabel = "§7● §cGiant planet";
        else sizeLabel = "§7● §4Supergiant (nearly flat)";
        ctx.drawCenteredString(font, Component.literal(sizeLabel), cx, iy - 14, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        assert minecraft != null;
        minecraft.setScreen(parent);
    }
}
