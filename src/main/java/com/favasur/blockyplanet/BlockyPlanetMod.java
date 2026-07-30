package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.world.BlockyPlanetChunkGenerator;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Mod(BlockyPlanetMod.MOD_ID)
public class BlockyPlanetMod {
    public static final String MOD_ID = "blocky_planet";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceLocation CHUNK_GENERATOR_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "blocky_planet_generator");

    /** Whether Tellus (real Earth terrain) is loaded alongside us. */
    public static final boolean TELLUS_LOADED;

    static {
        boolean t = false;
        try {
            t = ModList.get().isLoaded("tellus");
        } catch (Exception ignored) {}
        TELLUS_LOADED = t;
    }

    /** Volatile so worker threads see the update immediately. */
    public static volatile Level blockyWorld;

    /**
     * Reference to the Tellus overworld (used for reading surface blocks).
     * Set during server start when Tellus is loaded.
     */
    public static volatile Level tellusOverworld;

    public static final Set<WorldBorder> BLOCKY_BORDERS = ConcurrentHashMap.newKeySet();

    private static final Map<Level, PlanetBlockStorage> CUBE_STORAGE_MAP = new WeakHashMap<>();

    public BlockyPlanetMod(IEventBus modBus) {
        BlockyPlanetConfig.setPlanetDiameter(BlockyPlanetConfig.DEFAULT_DIAMETER);

        Registry.register(
                BuiltInRegistries.CHUNK_GENERATOR,
                CHUNK_GENERATOR_ID,
                BlockyPlanetChunkGenerator.CODEC
        );

        NeoForge.EVENT_BUS.addListener(BlockyPlanetMod::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BlockyPlanetMod::onPlayerJoinLevel);

        BlockyPlanetModClient.init();
        NeoForge.EVENT_BUS.addListener(BlockyPlanetModClient::onScreenInit);

        if (TELLUS_LOADED) {
            LOGGER.info("Tellus detected — planet surface reads Tellus terrain from overworld via projection.");
        } else {
            LOGGER.info("No Tellus — overworld uses Blocky Planet generator.");
        }

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
                BlockyPlanetConfig.getPlanetRadius(),
                BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }

    public static void onServerStarted(ServerStartedEvent event) {
        // Capture the overworld reference
        for (ServerLevel sl : event.getServer().getAllLevels()) {
            if (!isBlockyPlanetDimension(sl)) continue;

            blockyWorld = sl;
            LOGGER.info("Blocky Planet world: {}", sl.dimension().location());

            // Capture Tellus overworld reference
            if (TELLUS_LOADED) {
                for (ServerLevel overworld : event.getServer().getAllLevels()) {
                    if (overworld.dimension().location().equals(ResourceLocation.parse("minecraft:overworld"))) {
                        tellusOverworld = overworld;
                        LOGGER.info("Captured Tellus overworld reference");
                        break;
                    }
                }
            }
            break;
        }
    }

    /**
     * Teleport players to the planet surface when they join.
     * setDefaultSpawnPos is ignored for extreme Y values during login,
     * so direct teleport is used instead.
     */
    public static void onPlayerJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        Level world = event.getLevel();
        if (!isBlockyPlanetDimension(world)) return;

        double pr = QuadSphere.planetRadius();
        int surfaceY = (int) Math.round(pr) + 16;
        player.teleportTo((ServerLevel) world, 0.5, surfaceY, 0.5,
            java.util.Set.of(), 0.0f, 0.0f);
        LOGGER.info("Teleported player to planet surface at Y={}", surfaceY);
    }

    public static boolean isBlockyPlanetDimension(Level world) {
        if (world == null) return false;
        ResourceLocation id = world.dimension().location();
        // Only the overworld uses our generator (when Tellus is not loaded)
        if (!TELLUS_LOADED && id.equals(ResourceLocation.parse("minecraft:overworld"))) return true;
        return false;
    }

    /** Synchronized because WeakHashMap is not thread-safe. */
    public static synchronized PlanetBlockStorage getOrCreateStorage(Level world) {
        if (!isBlockyPlanetDimension(world)) {
            throw new IllegalStateException("Not a Blocky Planet dimension: " + world.dimension().location());
        }
        return CUBE_STORAGE_MAP.computeIfAbsent(world, w -> {
            LOGGER.info("Creating PlanetBlockStorage for world {}", w.dimension().location());
            return new PlanetBlockStorage();
        });
    }
}
