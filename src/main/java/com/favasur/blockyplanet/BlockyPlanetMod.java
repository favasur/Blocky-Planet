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

    public static final Identifier DIMENSION_ID = Identifier.of(MOD_ID, "blocky_planet");
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

        // Capture world references when the server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerWorld sw : server.getWorlds()) {
                // Capture Tellus overworld reference (used for surface block reading)
                if (TELLUS_LOADED && sw.getRegistryKey().equals(World.OVERWORLD)) {
                    tellusOverworld = sw;
                    LOGGER.info("Captured Tellus overworld reference");
                }
                // Capture our custom dimension
                if (isCustomDimension(sw)) {
                    blockyWorld = sw;
                    LOGGER.info("Captured Blocky Planet custom dimension: {}", sw.getRegistryKey().getValue());
                }
            }
            // Fall back to overworld if no custom dimension found
            if (blockyWorld == null) {
                for (ServerWorld sw : server.getWorlds()) {
                    if (isBlockyPlanetDimension(sw)) {
                        blockyWorld = sw;
                        LOGGER.info("Captured planet world: {}", sw.getRegistryKey().getValue());
                        break;
                    }
                }
            }

        });

        // ═══ Teleport players to planet surface on join ═══
        // Replaces setSpawnPos which fails at extreme Y values because
        // DimensionType.logicalHeight() = 384 rejects positions above it
        // even with height-limit mixins. Direct teleport bypasses this.
        //
        // IMPORTANT: server.execute() defers the teleport by one tick so it
        // runs AFTER the full login handshake. Without this delay the client
        // receives both the initial spawn and the teleport simultaneously,
        // triggering "You logged in from another location" disconnect.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
            (handler, sender, server) -> {
                net.minecraft.server.network.ServerPlayerEntity player = handler.getPlayer();
                if (!isBlockyPlanetDimension(player.getServerWorld())) return;
                server.execute(() -> teleportToSurface(player));
            });

        // ═══ Also handle respawn (JOIN only fires on initial connection) ═══
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
            (oldPlayer, newPlayer, alive) -> {
                if (!isBlockyPlanetDimension(newPlayer.getServerWorld())) return;
                teleportToSurface(newPlayer);
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
     * When Tellus is loaded, the overworld uses Tellus's generator (not ours),
     * so we only check our custom dimension ID here. The chunk generator's
     * surface reader will project sphere coordinates to overworld coordinates
     * and read Tellus blocks from {@link #tellusOverworld}.
     */
    public static boolean isBlockyPlanetDimension(World world) {
        if (world == null) return false;
        Identifier id = world.getRegistryKey().getValue();
        if (id.equals(DIMENSION_ID)) return true;
        // Only include overworld if Tellus is NOT loaded (otherwise Tellus controls overworld)
        if (!TELLUS_LOADED && id.equals(Identifier.ofVanilla("overworld"))) return true;
        return false;
    }

    /**
     * Returns true only for the dedicated Blocky Planet dimension (not overworld override).
     * Used by server startup to find the custom dimension preferentially.
     */
    private static boolean isCustomDimension(World world) {
        return world != null && world.getRegistryKey().getValue().equals(DIMENSION_ID);
    }

    /**
     * Teleport a player to the planet surface, bypassing logical-height
     * checks that would reject extreme Y values.
     */
    private static void teleportToSurface(net.minecraft.server.network.ServerPlayerEntity player) {
        double pr = QuadSphere.planetRadius();
        int surfaceY = (int) Math.round(pr) + 16;
        player.teleport(player.getServerWorld(), 0.5, surfaceY, 0.5,
            java.util.Set.of(), 0.0f, 0.0f);
        LOGGER.info("Teleported player to planet surface at Y={}", surfaceY);
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
