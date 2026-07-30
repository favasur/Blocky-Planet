package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.world.BlockyPlanetChunkGenerator;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
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

        // Capture the primary Blocky Planet world reference when the server starts
        // Prefer our custom dimension, fall back to any world using our generator
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerWorld sw : server.getWorlds()) {
                if (isCustomDimension(sw)) {
                    blockyWorld = sw;
                    LOGGER.info("Captured Blocky Planet custom dimension: {}", sw.getRegistryKey().getValue());
                    break;
                }
            }
            if (blockyWorld == null) {
                // Fall back to any world using our generator (e.g. overworld override)
                for (ServerWorld sw : server.getWorlds()) {
                    if (isBlockyPlanetDimension(sw)) {
                        blockyWorld = sw;
                        LOGGER.info("Captured override world: {}", sw.getRegistryKey().getValue());
                        break;
                    }
                }
            }
        });

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
            BlockyPlanetConfig.getPlanetRadius(),
            BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }

    /**
     * Returns true if this world uses our spherical Blocky Planet chunk generator.
     *
     * Checks both the dedicated {@code blocky_planet:blocky_planet} dimension and
     * the vanilla overworld, which we override in our datapack data to use our generator.
     *
     * This is used by mixins to decide if cubic-world block storage should be active.
     */
    public static boolean isBlockyPlanetDimension(World world) {
        if (world == null) return false;
        Identifier id = world.getRegistryKey().getValue();
        return id.equals(DIMENSION_ID) || id.equals(Identifier.ofVanilla("overworld"));
    }

    /**
     * Returns true only for the dedicated Blocky Planet dimension (not overworld override).
     * Used by server startup to find the custom dimension preferentially.
     */
    private static boolean isCustomDimension(World world) {
        return world != null && world.getRegistryKey().getValue().equals(DIMENSION_ID);
    }

    public static PlanetBlockStorage getOrCreateStorage(World world) {
        if (!isBlockyPlanetDimension(world)) {
            throw new IllegalStateException("Not a Blocky Planet world: " + world.getRegistryKey().getValue());
        }
        return CUBE_STORAGE_MAP.computeIfAbsent(world, w -> {
            LOGGER.info("Creating PlanetBlockStorage for world {}", w.getRegistryKey().getValue());
            return new PlanetBlockStorage();
        });
    }
}
