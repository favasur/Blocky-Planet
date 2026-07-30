package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.world.BlockyPlanetChunkGenerator;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
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

    public static final Identifier CHUNK_GENERATOR_ID = Identifier.of(MOD_ID, "blocky_planet_generator");

    /** Whether Tellus (real Earth terrain) is loaded alongside us. */
    public static final boolean TELLUS_LOADED;

    static {
        boolean t = false;
        try {
            t = FabricLoader.getInstance().isModLoaded("tellus");
        } catch (Exception ignored) {}
        TELLUS_LOADED = t;
    }

    /**
     * Accessed by the chunk generator during populateNoise (worker threads)
     * and set by the SERVER_STARTED listener (server thread). Volatile ensures
     * worker threads see the update immediately.
     */
    public static volatile World blockyWorld;

    /**
     * Reference to the overworld (used by Tellus surface reader).
     * Set during server start when Tellus is loaded.
     */
    public static volatile World tellusOverworld;

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

        // Capture the overworld and set spawn position on server start
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerWorld sw : server.getWorlds()) {
                if (!isBlockyPlanetDimension(sw)) continue;

                blockyWorld = sw;
                LOGGER.info("Blocky Planet world: {}", sw.getRegistryKey().getValue());

                // Set the world spawn position at the planet surface.
                // The MixinHeightLimitView makes isOutOfHeightLimit return false
                // for any Y value, so setSpawnPos can place the player at the
                // correct surface height regardless of DimensionType.logicalHeight().
                double pr = QuadSphere.planetRadius();
                int surfaceY = (int) Math.round(pr) + 16;
                BlockPos spawnPos = new BlockPos(0, surfaceY, 0);
                sw.setSpawnPos(spawnPos, 0);
                LOGGER.info("Set world spawn to {}", spawnPos);

                // Capture Tellus overworld reference for surface block reading
                if (TELLUS_LOADED) {
                    for (ServerWorld overworld : server.getWorlds()) {
                        if (overworld.getRegistryKey().equals(World.OVERWORLD)) {
                            tellusOverworld = overworld;
                            LOGGER.info("Captured Tellus overworld reference");
                            break;
                        }
                    }
                }
                break;
            }
        });

        if (TELLUS_LOADED) {
            LOGGER.info("Tellus detected — planet surface reads Tellus terrain from overworld via projection.");
        } else {
            LOGGER.info("No Tellus — overworld uses Blocky Planet generator.");
        }

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
            BlockyPlanetConfig.getPlanetRadius(),
            BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }

    /**
     * Returns true if this world uses our spherical Blocky Planet chunk generator.
     *
     * The planet IS the overworld — we replace the overworld dimension with our
     * chunk generator via the dimension override JSON. No custom dimension needed.
     * When Tellus is loaded, the overworld uses Tellus's generator, so we return
     * false in that case (Tellus controls overworld generation).
     */
    public static boolean isBlockyPlanetDimension(World world) {
        if (world == null) return false;
        // Only the overworld uses our generator (when Tellus is not loaded)
        if (!TELLUS_LOADED && world.getRegistryKey().getValue().equals(Identifier.ofVanilla("overworld"))) return true;
        return false;
    }

    /**
     * Get or create the PlanetBlockStorage for the given world.
     *
     * Synchronized because Minecraft's chunk generation thread pool calls
     * this from multiple threads concurrently, and {@link WeakHashMap} is
     * not thread-safe (concurrent access can corrupt its internal hash
     * chains, causing infinite loops).
     */
    public static synchronized PlanetBlockStorage getOrCreateStorage(World world) {
        if (!isBlockyPlanetDimension(world)) {
            throw new IllegalStateException("Not a Blocky Planet world: " + world.getRegistryKey().getValue());
        }
        return CUBE_STORAGE_MAP.computeIfAbsent(world, w -> {
            LOGGER.info("Creating PlanetBlockStorage for world {}", w.getRegistryKey().getValue());
            return new PlanetBlockStorage();
        });
    }
}
