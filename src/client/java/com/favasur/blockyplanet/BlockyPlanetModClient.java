package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.gui.PlanetSizeScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Client entry point. Adds a "Planet Size" button to the Create World screen.
 *
 * Uses an access widener rather than a mixin invoker to call Screen's
 * protected {@code addDrawableChild} method, because mixin invokers
 * fail through Sinytra Connector (method name resolution issue).
 *
 * @see <a href="https://fabricmc.net/wiki/tutorial:accesswideners">Fabric Access Wideners</a>
 */
@Environment(EnvType.CLIENT)
public class BlockyPlanetModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockyPlanetMod.LOGGER.info("Blocky Planet client initialized — registering world creation hook.");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof CreateWorldScreen) {
                addPlanetSizeButton(client, screen);
            }
        });
    }

    private void addPlanetSizeButton(MinecraftClient client, Screen screen) {
        int btnW = 130;
        int btnH = 20;
        int rightX = (screen.width / 2) + 8;
        int topY = 60;

        ButtonWidget btn = ButtonWidget.builder(
            Text.literal("⏺ Planet: " + formatDiameter(BlockyPlanetConfig.getPlanetDiameter())),
            b -> {
                PlanetSizeScreen configScreen = new PlanetSizeScreen(
                    screen,
                    selectedDiameter -> {
                        String newLabel = "⏺ Planet: " + formatDiameter(selectedDiameter);
                        b.setMessage(Text.literal(newLabel));
                    }
                );
                client.setScreen(configScreen);
            }
        ).dimensions(rightX, topY, btnW, btnH).build();

        // Access widener makes addDrawableChild accessible without mixin invoker
        screen.addDrawableChild(btn);
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
