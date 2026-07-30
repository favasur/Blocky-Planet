package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.gui.PlanetSizeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Client entry point. Adds a "Planet Size" button to the Create World screen.
 * Uses an access transformer to call Screen's protected addRenderableWidget method.
 */
@OnlyIn(Dist.CLIENT)
public class BlockyPlanetModClient {

    public static void init() {
        BlockyPlanetMod.LOGGER.info("Blocky Planet client initialized — registering world creation hook.");
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof CreateWorldScreen) {
            addPlanetSizeButton(screen);
        }
    }

    private static void addPlanetSizeButton(Screen screen) {
        int btnW = 130;
        int btnH = 20;
        int rightX = (screen.width / 2) + 8;
        int topY = 60;

        Button btn = Button.builder(
            Component.literal("⏺ Planet: " + formatDiameter(BlockyPlanetConfig.getPlanetDiameter())),
            b -> {
                PlanetSizeScreen configScreen = new PlanetSizeScreen(
                    screen,
                    selectedDiameter -> {
                        String newLabel = "⏺ Planet: " + formatDiameter(selectedDiameter);
                        b.setMessage(Component.literal(newLabel));
                    }
                );
                Minecraft.getInstance().setScreen(configScreen);
            }
        ).bounds(rightX, topY, btnW, btnH).build();

        // Access transformer makes addRenderableWidget accessible
        screen.addRenderableWidget(btn);
    }

    private static String formatDiameter(int d) {
        if (d < 1000) {
            return d + " blk";
        } else if (d < 100_000) {
            return String.format("%.1f km", d / 1000.0);
        } else {
            return String.format("%.0f km", d / 1000.0);
        }
    }
}
