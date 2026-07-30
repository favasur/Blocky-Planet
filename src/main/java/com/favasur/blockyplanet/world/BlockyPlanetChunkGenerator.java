package com.favasur.blockyplanet.world;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.planet.BlockAddress;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.planet.Vector3d;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.server.level.WorldGenRegion;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk generator for the spherical Blocky Planet world (NeoForge).
 *
 * Layer structure (from surface inward):
 *   ┌──────────────────────────────────────────┐
 *   │ ① Surface (depth 0–1 block)             │  Biome-dependant top block
 *   │ ② Subsurface (depth 1–4 blocks)         │  Dirt, sand, or sandstone
 *   │ ③ Stone crust (depth 4 → nether outer)  │  Stone with ore veins
 *   │ ④ Nether ring (spherical shell)         │  Vanilla Nether biomes + caves
 *   │ ⑤ Endless lava (nether inner → core)    │  Lava
 *   │ ⑥ Hollow core (below shellInner)        │  Air (void)
 *   └──────────────────────────────────────────┘
 */
public class BlockyPlanetChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BlockyPlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
        ).apply(instance, BlockyPlanetChunkGenerator::new)
    );

    private static final double TERRAIN_AMPLITUDE = 12.0;
    private static final double NOISE_SCALE = 0.03;

    /** Soil depth in blocks (top + subsurface layers). */
    private static final int SOIL_DEPTH = 4;

    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite biomeNoise;
    private final FastNoiseLite caveNoise;
    private final FastNoiseLite oreNoise;
    private final NetherBiomeHelper netherBiomeHelper;

    public BlockyPlanetChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
        this.terrainNoise = createTerrainNoise();
        this.biomeNoise = createBiomeNoise();
        this.caveNoise = createCaveNoise();
        this.oreNoise = createOreNoise();
        this.netherBiomeHelper = new NetherBiomeHelper(QuadSphere.planetRadius());
    }

    // ─── Noise generators ─────────────────────────────────────────────────

    private static FastNoiseLite createTerrainNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(42);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(NOISE_SCALE);
        noise.SetFractalType(FastNoiseLite.FractalType.FBM);
        noise.SetFractalOctaves(3);
        noise.SetFractalLacunarity(2.0);
        noise.SetFractalGain(0.5);
        return noise;
    }

    private static FastNoiseLite createBiomeNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(99);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(0.01);
        return noise;
    }

    private static FastNoiseLite createCaveNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(666);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(0.02);
        noise.SetFractalType(FastNoiseLite.FractalType.FBM);
        noise.SetFractalOctaves(2);
        noise.SetFractalLacunarity(2.0);
        noise.SetFractalGain(0.5);
        return noise;
    }

    private static FastNoiseLite createOreNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(999);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(0.08);
        noise.SetFractalType(FastNoiseLite.FractalType.None);
        return noise;
    }

    // ─── Chunk population ─────────────────────────────────────────────────

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
                              BiomeManager biomeManager, StructureManager structureManager,
                              ChunkAccess chunk, GenerationStep.Carving carving) {}

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                              RandomState random, ChunkAccess chunk) {}

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
                                                          StructureManager structureManager, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        double planetRadius = QuadSphere.planetRadius();
        double maxReach = planetRadius + TERRAIN_AMPLITUDE;

        PlanetBlockStorage storage = null;
        Level world = BlockyPlanetMod.blockyWorld;
        if (world != null) {
            storage = BlockyPlanetMod.getOrCreateStorage(world);
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int chunkBottomY = chunk.getMinBuildHeight();
        int chunkTopY = chunk.getMaxBuildHeight() - 1;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = chunkX * 16 + dx;
                int wz = chunkZ * 16 + dz;

                double xyDistSq = (double) wx * wx + (double) wz * wz;
                double maxYSq = maxReach * maxReach - xyDistSq;
                if (maxYSq < 0) continue;

                int yBound = (int) Math.floor(Math.sqrt(maxYSq));

                int startY = Math.max(-yBound, chunkBottomY);
                int endY   = Math.min(yBound, chunkTopY);
                if (startY > endY) continue;

                for (int wy = startY; wy <= endY; wy++) {
                    double distFromCenter = Math.sqrt(xyDistSq + (double) wy * wy);
                    BlockState state = getGravityAlignedBlock(wx, wy, wz, distFromCenter);
                    if (state != null) {
                        cursor.set(wx, wy, wz);
                        chunk.setBlockState(cursor, state, false);
                        if (storage != null) {
                            storage.setBlockState(wx, wy, wz, state);
                            BlockAddress addr = BlockAddress.fromWorldPosition(new Vector3d(wx, wy, wz));
                            Vector3d normal = addr.getSurfaceNormal();
                            if (normal != null) storage.setNormal(wx, wy, wz, normal);
                        }
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // ─── Block selection ──────────────────────────────────────────────────

    /**
     * Determine what block (if any) to place at the given world position.
     *
     * Layers from surface inward:
     *   distance > surfaceRadius      → space (water or air)
     *   surface to surface-SOIL_DEPTH → soil (top → subsurface)
     *   SOIL_DEPTH below surface to Nether outer → stone crust
     *   Nether ring                         → Nether biomes
     *   Nether inner to shell inner          → lava
     *   below shell inner                    → hollow core (void)
     */
    private BlockState getGravityAlignedBlock(int x, int y, int z, double distFromCenter) {
        Vector3d worldPos = new Vector3d(x, y, z);
        BlockAddress addr = BlockAddress.fromWorldPosition(worldPos);
        Vector3d alignedPos = addr.toWorldPositionImproved();
        double alignedDist = alignedPos.length();

        double planetRadius = QuadSphere.planetRadius();
        double surfaceRadius = getSurfaceRadius(alignedPos);
        double depthBelowSurface = surfaceRadius - alignedDist;

        // ──────── ① Above surface ──────────────────────────────────────────
        if (depthBelowSurface < 0) {
            double waterRadius = planetRadius * 0.95;
            if (alignedDist <= waterRadius && alignedDist > QuadSphere.getShellInnerRadius(0)) {
                return Blocks.WATER.defaultBlockState();
            }
            return null; // Air
        }

        // ──────── ⑥ Hollow core ────────────────────────────────────────────
        if (alignedDist < QuadSphere.getShellInnerRadius(0)) return null;

        // Warp check – blocks far from their gravity-aligned position use fallback
        double warpDist = worldPos.subtract(alignedPos).length();
        if (warpDist > 0.6) return getFallbackBlock(x, y, z, distFromCenter);

        // ──────── ④ Nether ring ────────────────────────────────────────────
        if (BlockyPlanetConfig.isInNetherRing(alignedDist)) {
            return getNetherBlock(alignedPos, alignedDist, planetRadius);
        }

        // ──────── ⑤ Lava layer (below Nether to core) ──────────────────────
        if (alignedDist < BlockyPlanetConfig.getNetherInnerRadius(planetRadius)) {
            return Blocks.LAVA.defaultBlockState();
        }

        // ──────── ② + ③ Crust: soil + stone ───────────────────────────────
        if (depthBelowSurface <= 1.0) {
            return getSurfaceBlock(alignedPos, alignedDist, false);
        } else if (depthBelowSurface <= SOIL_DEPTH) {
            return getSubsurfaceBlock(alignedPos, alignedDist);
        } else {
            BlockState ore = getOreBlock(alignedPos, depthBelowSurface);
            return ore != null ? ore : Blocks.STONE.defaultBlockState();
        }
    }

    // ─── Fallback (for positions with high warp) ──────────────────────────

    private BlockState getFallbackBlock(int x, int y, int z, double distFromCenter) {
        double planetRadius = QuadSphere.planetRadius();
        if (distFromCenter > planetRadius + TERRAIN_AMPLITUDE) return null;
        if (distFromCenter < QuadSphere.getShellInnerRadius(0)) return null;

        double surfaceRadius = getSurfaceRadius(new Vector3d(x, y, z));
        double depth = surfaceRadius - distFromCenter;
        if (depth < 0) return null;

        if (BlockyPlanetConfig.isInNetherRing(distFromCenter)) {
            return getNetherBlock(new Vector3d(x, y, z), distFromCenter, planetRadius);
        }

        if (distFromCenter < BlockyPlanetConfig.getNetherInnerRadius(planetRadius)) {
            return Blocks.LAVA.defaultBlockState();
        }

        if (depth <= SOIL_DEPTH) {
            Vector3d pos = new Vector3d(x, y, z);
            if (depth <= 1.0) return getSurfaceBlock(pos, distFromCenter, true);
            return getSubsurfaceBlock(pos, distFromCenter);
        }
        return Blocks.STONE.defaultBlockState();
    }

    // ─── Surface / soil blocks ────────────────────────────────────────────

    private BlockState getSurfaceBlock(Vector3d alignedPos, double alignedDist, boolean isFallback) {
        double planetRadius = QuadSphere.planetRadius();
        double waterRadius = planetRadius * 0.95;

        // Underwater (ocean floor)
        if (alignedDist <= waterRadius) {
            double gravelChance = isFallback ? 0.3 : biomeNoise.GetNoise(
                alignedPos.x() * 0.1, alignedPos.y() * 0.1, alignedPos.z() * 0.1);
            return gravelChance > 0.4 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        }

        // Arctic (snowy poles)
        if (isArcticRegion(alignedPos)) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }

        // Desert
        if (!isFallback) {
            if (biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.25) {
                return Blocks.SAND.defaultBlockState();
            }
        }

        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState getSubsurfaceBlock(Vector3d alignedPos, double alignedDist) {
        double planetRadius = QuadSphere.planetRadius();
        double sandRadius = planetRadius * 0.97;

        if (alignedDist <= sandRadius || !isArcticRegion(alignedPos)) {
            if (biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.2) {
                return Blocks.SANDSTONE.defaultBlockState();
            }
        }

        return Blocks.DIRT.defaultBlockState();
    }

    // ─── Ore generation ───────────────────────────────────────────────────

    private BlockState getOreBlock(Vector3d alignedPos, double depthBelowSurface) {
        double ore = oreNoise.GetNoise(alignedPos.x(), alignedPos.y(), alignedPos.z());

        if (ore > 0.70 && depthBelowSurface >= 5 && depthBelowSurface <= 100) {
            return Blocks.COAL_ORE.defaultBlockState();
        }
        if (ore > 0.78 && depthBelowSurface >= 10 && depthBelowSurface <= 60) {
            return Blocks.IRON_ORE.defaultBlockState();
        }
        if (ore > 0.82 && depthBelowSurface >= 15 && depthBelowSurface <= 50) {
            return Blocks.COPPER_ORE.defaultBlockState();
        }
        if (ore > 0.88 && depthBelowSurface >= 20 && depthBelowSurface <= 40) {
            return Blocks.GOLD_ORE.defaultBlockState();
        }
        if (ore > 0.90 && depthBelowSurface >= 20 && depthBelowSurface <= 30) {
            return Blocks.REDSTONE_ORE.defaultBlockState();
        }
        if (ore > 0.93 && depthBelowSurface >= 20 && depthBelowSurface <= 35) {
            return Blocks.LAPIS_ORE.defaultBlockState();
        }
        if (ore > 0.95 && depthBelowSurface >= 25 && depthBelowSurface <= 30) {
            return Blocks.DIAMOND_ORE.defaultBlockState();
        }

        return null;
    }

    // ─── Nether ring ──────────────────────────────────────────────────────

    private BlockState getNetherBlock(Vector3d pos, double distFromCenter, double planetRadius) {
        double px = pos.x(), py = pos.y(), pz = pos.z();
        NetherBiomeHelper.NetherBiome biome = netherBiomeHelper.getBiome(px, py, pz);
        double noiseVal = caveNoise.GetNoise(px * 0.02, py * 0.02, pz * 0.02);
        double inner = BlockyPlanetConfig.getNetherInnerRadius(planetRadius);
        double outer = BlockyPlanetConfig.getNetherOuterRadius(planetRadius);
        double thick = outer - inner;
        double depth = (distFromCenter - inner) / thick;

        if (depth < 2.0 / thick || depth > 1.0 - 2.0 / thick) return Blocks.BEDROCK.defaultBlockState();

        double caveThreshold = NetherBiomeHelper.isDenseBiome(biome) ? -0.1 : -0.4;
        if (noiseVal < caveThreshold) {
            double lavaThresh = NetherBiomeHelper.getLavaThreshold(biome);
            return (depth < lavaThresh && noiseVal < -0.5) ? Blocks.LAVA.defaultBlockState() : null;
        }

        boolean nearCave = isNearCave(px, py, pz);
        if (nearCave && depth > 0.5) return NetherBiomeHelper.getCeilingBlock(biome);
        if (nearCave) return NetherBiomeHelper.getTopBlock(biome);
        BlockState decor = NetherBiomeHelper.getDecorationBlock(biome, noiseVal);
        return decor != null ? decor : NetherBiomeHelper.getBaseBlock(biome);
    }

    private boolean isNearCave(double x, double y, double z) {
        double center = caveNoise.GetNoise(x * 0.02, y * 0.02, z * 0.02);
        if (center < -0.3) return true;
        return caveNoise.GetNoise((x + 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise((x - 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y + 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y - 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z + 1) * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z - 1) * 0.02) < -0.3;
    }

    // ─── Terrain noise helpers ────────────────────────────────────────────

    private double getSurfaceRadius(Vector3d pos) {
        double noiseVal = terrainNoise.GetNoise(pos.x() * NOISE_SCALE, pos.y() * NOISE_SCALE, pos.z() * NOISE_SCALE);
        return QuadSphere.planetRadius() + noiseVal * TERRAIN_AMPLITUDE;
    }

    private boolean isArcticRegion(Vector3d pos) {
        double dist = pos.length();
        if (dist < 1) return false;
        double ny = pos.y() / dist;
        double polarAngle = Math.acos(Math.abs(ny));
        double noiseVal = biomeNoise.GetNoise(pos.x() * 0.1, pos.y() * 0.1, pos.z() * 0.1);
        double threshold = 0.5 + noiseVal * 0.15;
        return polarAngle < threshold;
    }

    // ─── Vanilla API overrides ────────────────────────────────────────────

    @Override
    public int getMinY() { return 0; }

    @Override
    public int getSeaLevel() { return 0; }

    @Override
    public int getGenDepth() { return 16; }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState random) {
        BlockState[] air = new BlockState[getGenDepth()];
        for (int i = 0; i < air.length; i++) air[i] = Blocks.AIR.defaultBlockState();
        return new NoiseColumn(0, air);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmap, LevelHeightAccessor world, RandomState noiseConfig) {
        double planetR = QuadSphere.planetRadius();
        double distSq = (double) x * x + (double) z * z;
        if (distSq > planetR * planetR) return 0;
        double noiseVal = terrainNoise.GetNoise(x * NOISE_SCALE, 0, z * NOISE_SCALE);
        double y = Math.sqrt(planetR * planetR - distSq);
        return (int) Math.round(y + noiseVal * TERRAIN_AMPLITUDE);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public void addDebugScreenInfo(List<String> text, RandomState noiseConfig, BlockPos pos) {
        double planetRadius = QuadSphere.planetRadius();
        text.add("§6Blocky Planet");
        text.add(String.format("§7Planet: §f%s ⌀  §7(%s radius)",
            BlockyPlanetConfig.formatDiameter((int) (planetRadius * 2)),
            BlockyPlanetConfig.formatRadius(planetRadius)));

        Vector3d v = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        double dist = v.length();
        double surfaceRadius = getSurfaceRadius(v);
        text.add(String.format("§7Surface: §f%.1f  §7Dist: §f%.1f  §7Depth: §f%.1f",
            surfaceRadius, dist, surfaceRadius - dist));

        String layer = dist < QuadSphere.getShellInnerRadius(0) ? "§7Layer: §6Core" :
                       dist < BlockyPlanetConfig.getNetherInnerRadius(planetRadius) ? "§7Layer: §cLava" :
                       BlockyPlanetConfig.isInNetherRing(dist) ? "§7Layer: §4Nether" :
                       surfaceRadius - dist <= SOIL_DEPTH ? "§7Layer: §aSurface" :
                       "§7Layer: §8Crust";
        text.add(layer);

        try {
            BlockAddress addr = BlockAddress.fromWorldPosition(v);
            Vector3d aligned = addr.toWorldPositionImproved();
            double offset = v.subtract(aligned).length();
            text.add(String.format("§7Addr: %s  §7offset: %.2f", addr, offset));
            Level w = BlockyPlanetMod.blockyWorld;
            if (w != null) {
                PlanetBlockStorage s = BlockyPlanetMod.getOrCreateStorage(w);
                text.add(String.format("§7Cubes in storage: §f%d", s.size()));
            }
        } catch (Exception e) {
            text.add("§7Addr: error");
        }
    }
}
