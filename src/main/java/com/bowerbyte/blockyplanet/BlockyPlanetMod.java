package com.bowerbyte.blockyplanet;

import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;
import com.bowerbyte.blockyplanet.world.BlockyPlanetChunkGenerator;
import com.bowerbyte.blockyplanet.world.cube.PlanetBlockStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class BlockyPlanetMod implements ModInitializer {
    public static final String MOD_ID = "blocky_planet";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier DIMENSION_ID = Identifier.of(MOD_ID, "blocky_planet");
    public static final Identifier CHUNK_GENERATOR_ID = Identifier.of(MOD_ID, "blocky_planet_generator");

    /** Accessed by the chunk generator during populateNoise. Set when the server starts. */
    public static World blockyWorld;

    /** Set of WorldBorder instances that belong to the Blocky Planet dimension. */
    public static final Set<WorldBorder> BLOCKY_BORDERS = ConcurrentHashMap.newKeySet();

    private static final Map<World, PlanetBlockStorage> CUBE_STORAGE_MAP = new WeakHashMap<>();

    @Override
    public void onInitialize() {
        BlockyPlanetConfig.setPlanetDiameter(BlockyPlanetConfig.DEFAULT_DIAMETER);

        Registry.register(
            net.minecraft.registry.Registries.CHUNK_GENERATOR,
            CHUNK_GENERATOR_ID,
            BlockyPlanetChunkGenerator.CODEC
        );

        // Capture the Blocky Planet world reference when the server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerWorld sw : server.getWorlds()) {
                if (isBlockyPlanetDimension(sw)) {
                    blockyWorld = sw;
                    LOGGER.info("Captured Blocky Planet world reference: {}", sw.getRegistryKey().getValue());
                    break;
                }
            }
        });

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
            BlockyPlanetConfig.getPlanetRadius(),
            BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }

    public static boolean isBlockyPlanetDimension(World world) {
        return world != null && world.getRegistryKey().getValue().equals(DIMENSION_ID);
    }

    public static PlanetBlockStorage getOrCreateStorage(World world) {
        if (!isBlockyPlanetDimension(world)) {
            throw new IllegalStateException("Not a Blocky Planet dimension: " + world.getRegistryKey().getValue());
        }
        return CUBE_STORAGE_MAP.computeIfAbsent(world, w -> {
            LOGGER.info("Creating PlanetBlockStorage for world {}", w.getRegistryKey().getValue());
            return new PlanetBlockStorage();
        });
    }
}
