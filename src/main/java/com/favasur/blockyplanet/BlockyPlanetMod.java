package com.favasur.blockyplanet;

import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.world.BlockyPlanetChunkGenerator;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.bus.api.IEventBus;
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

    public static final ResourceLocation DIMENSION_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "blocky_planet");
    public static final ResourceLocation CHUNK_GENERATOR_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "blocky_planet_generator");

    public static Level blockyWorld;

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

        BlockyPlanetModClient.init();
        NeoForge.EVENT_BUS.addListener(BlockyPlanetModClient::onScreenInit);

        LOGGER.info("Blocky Planet initialized! Default planet radius: {} blocks ({} km)",
                BlockyPlanetConfig.getPlanetRadius(),
                BlockyPlanetConfig.getPlanetRadius() / 1000.0);
    }

    public static void onServerStarted(ServerStartedEvent event) {
        for (ServerLevel sl : event.getServer().getAllLevels()) {
            if (isBlockyPlanetDimension(sl)) {
                blockyWorld = sl;
                LOGGER.info("Captured Blocky Planet world reference: {}", sl.dimension().location());
                break;
            }
        }
    }

    public static boolean isBlockyPlanetDimension(Level world) {
        return world != null && world.dimension().location().equals(DIMENSION_ID);
    }

    public static PlanetBlockStorage getOrCreateStorage(Level world) {
        if (!isBlockyPlanetDimension(world)) {
            throw new IllegalStateException("Not a Blocky Planet dimension: " + world.dimension().location());
        }
        return CUBE_STORAGE_MAP.computeIfAbsent(world, w -> {
            LOGGER.info("Creating PlanetBlockStorage for world {}", w.dimension().location());
            return new PlanetBlockStorage();
        });
    }
}
