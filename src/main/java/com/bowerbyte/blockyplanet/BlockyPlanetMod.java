package com.bowerbyte.blockyplanet;

import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;
import com.bowerbyte.blockyplanet.world.BlockyPlanetChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockyPlanetMod implements ModInitializer {
    public static final String MOD_ID = "blocky_planet";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** ID for the dimension itself (used in /execute in blocky_planet:blocky_planet). */
    public static final Identifier DIMENSION_ID = Identifier.of(MOD_ID, "blocky_planet");

    /** ID for the custom chunk generator type. */
    public static final Identifier CHUNK_GENERATOR_ID = Identifier.of(MOD_ID, "blocky_planet_generator");

    @Override
    public void onInitialize() {
        // Set default planet diameter
        BlockyPlanetConfig.setPlanetDiameter(BlockyPlanetConfig.DEFAULT_DIAMETER);

        // Register the custom chunk generator codec type
        Registry.register(
            net.minecraft.registry.Registries.CHUNK_GENERATOR,
            CHUNK_GENERATOR_ID,
            BlockyPlanetChunkGenerator.CODEC
        );

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
            BlockyPlanetConfig.getPlanetRadius(),
            BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }
}
