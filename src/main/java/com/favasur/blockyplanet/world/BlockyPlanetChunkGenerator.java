package com.favasur.blockyplanet.world;

import com.favasur.blockyplanet.BlockyPlanetMod;
import com.favasur.blockyplanet.config.BlockyPlanetConfig;
import com.favasur.blockyplanet.planet.BlockAddress;
import com.favasur.blockyplanet.planet.QuadSphere;
import com.favasur.blockyplanet.planet.Vector3d;
import com.favasur.blockyplanet.world.cube.PlanetBlockStorage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk generator for the spherical Blocky Planet world.
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
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig,
                       BiomeAccess biomeAccess, StructureAccessor structureAccessor,
                       Chunk chunk, GenerationStep.Carver carverStep) {}

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures,
                              NoiseConfig noiseConfig, Chunk chunk) {}

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig,
                                                    StructureAccessor structureAccessor, Chunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        double planetRadius = QuadSphere.planetRadius();
        double maxReach = planetRadius + TERRAIN_AMPLITUDE;

        PlanetBlockStorage storage = null;
        World world = BlockyPlanetMod.blockyWorld;
        if (world != null) {
            storage = BlockyPlanetMod.getOrCreateStorage(world);
        }

        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int chunkBottomY = chunk.getBottomY();
        int chunkTopY = chunk.getTopY() - 1;

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
            // Water fills everything from 95% radius up to surface
            double waterRadius = planetRadius * 0.95;
            if (alignedDist <= waterRadius && alignedDist > QuadSphere.getShellInnerRadius(0)) {
                return Blocks.WATER.getDefaultState();
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
            return Blocks.LAVA.getDefaultState();
        }

        // ──────── ② + ③ Crust: soil + stone ───────────────────────────────
        if (depthBelowSurface <= 1.0) {
            // ②a Surface top block (biome-dependent)
            return getSurfaceBlock(alignedPos, alignedDist, false);
        } else if (depthBelowSurface <= SOIL_DEPTH) {
            // ②b Subsurface block (dirt, sand, or sandstone)
            return getSubsurfaceBlock(alignedPos, alignedDist);
        } else {
            // ③ Stone crust with ores
            BlockState ore = getOreBlock(alignedPos, depthBelowSurface);
            return ore != null ? ore : Blocks.STONE.getDefaultState();
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

        // Nether ring check
        if (BlockyPlanetConfig.isInNetherRing(distFromCenter)) {
            return getNetherBlock(new Vector3d(x, y, z), distFromCenter, planetRadius);
        }

        // Lava below Nether
        if (distFromCenter < BlockyPlanetConfig.getNetherInnerRadius(planetRadius)) {
            return Blocks.LAVA.getDefaultState();
        }

        if (depth <= SOIL_DEPTH) {
            Vector3d pos = new Vector3d(x, y, z);
            if (depth <= 1.0) return getSurfaceBlock(pos, distFromCenter, true);
            return getSubsurfaceBlock(pos, distFromCenter);
        }
        return Blocks.STONE.getDefaultState();
    }

    // ─── Surface / soil blocks ────────────────────────────────────────────

    /**
     * Return the top block for a surface position.
     *
     * Determines the biome from latitude + noise, then picks the appropriate
     * vanilla-like block:
     *   Arctic (poles)        → Snow block
     *   Desert (hot/low rain) → Sand
     *   Ocean floor           → Gravel
     *   Default               → Grass block
     */
    private BlockState getSurfaceBlock(Vector3d alignedPos, double alignedDist, boolean isFallback) {
        double planetRadius = QuadSphere.planetRadius();
        double waterRadius = planetRadius * 0.95;

        // Underwater (ocean floor)
        if (alignedDist <= waterRadius) {
            double gravelChance = isFallback ? 0.3 : biomeNoise.GetNoise(
                alignedPos.x() * 0.1, alignedPos.y() * 0.1, alignedPos.z() * 0.1);
            return gravelChance > 0.4 ? Blocks.GRAVEL.getDefaultState() : Blocks.SAND.getDefaultState();
        }

        // Arctic (snowy poles)
        if (isArcticRegion(alignedPos)) {
            return Blocks.SNOW_BLOCK.getDefaultState();
        }

        // Desert
        if (!isFallback) {
            if (biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.25) {
                return Blocks.SAND.getDefaultState();
            }
        }

        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    /**
     * Return the block just below the surface (depth 1-4).
     * Matches the surface biome: dirt for grass/snow, sandstone for desert, sand for ocean.
     */
    private BlockState getSubsurfaceBlock(Vector3d alignedPos, double alignedDist) {
        double planetRadius = QuadSphere.planetRadius();
        double waterRadius = planetRadius * 0.97;

        // Near equator / desert → sand
        if (alignedDist <= waterRadius || !isArcticRegion(alignedPos)) {
            if (biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.2) {
                return Blocks.SANDSTONE.getDefaultState();
            }
        }

        return Blocks.DIRT.getDefaultState();
    }

    // ─── Ore generation ───────────────────────────────────────────────────

    /**
     * Simple noise-based ore veins in the stone crust.
     *
     * Gives a vanilla-like distribution loosely based on depth:
     *   Coal:       depth 5–100, common
     *   Iron:       depth 10–60,  moderate
     *   Copper:     depth 15–50,  moderate
     *   Gold:       depth 20–40,  rare
     *   Redstone:   depth 20–30,  rare (multiple)
     *   Lapis:      depth 20–35,  very rare (single)
     *   Diamond:    depth 25–30,  very rare (single)
     */
    private BlockState getOreBlock(Vector3d alignedPos, double depthBelowSurface) {
        double ore = oreNoise.GetNoise(alignedPos.x(), alignedPos.y(), alignedPos.z());

        // Coal (common, wide depth range)
        if (ore > 0.70 && depthBelowSurface >= 5 && depthBelowSurface <= 100) {
            return Blocks.COAL_ORE.getDefaultState();
        }
        // Iron (common, mid-depth)
        if (ore > 0.78 && depthBelowSurface >= 10 && depthBelowSurface <= 60) {
            return Blocks.IRON_ORE.getDefaultState();
        }
        // Copper (moderate)
        if (ore > 0.82 && depthBelowSurface >= 15 && depthBelowSurface <= 50) {
            return Blocks.COPPER_ORE.getDefaultState();
        }
        // Gold (rara, deeper)
        if (ore > 0.88 && depthBelowSurface >= 20 && depthBelowSurface <= 40) {
            return Blocks.GOLD_ORE.getDefaultState();
        }
        // Redstone (rara, deep)
        if (ore > 0.90 && depthBelowSurface >= 20 && depthBelowSurface <= 30) {
            return Blocks.REDSTONE_ORE.getDefaultState();
        }
        // Lapis (very rara, deep)
        if (ore > 0.93 && depthBelowSurface >= 20 && depthBelowSurface <= 35) {
            return Blocks.LAPIS_ORE.getDefaultState();
        }
        // Diamond (extremely rara, specific depth)
        if (ore > 0.95 && depthBelowSurface >= 25 && depthBelowSurface <= 30) {
            return Blocks.DIAMOND_ORE.getDefaultState();
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

        // Bedrock seal at both boundaries
        if (depth < 2.0 / thick || depth > 1.0 - 2.0 / thick) return Blocks.BEDROCK.getDefaultState();

        // Cave carving
        double caveThreshold = NetherBiomeHelper.isDenseBiome(biome) ? -0.1 : -0.4;
        if (noiseVal < caveThreshold) {
            double lavaThresh = NetherBiomeHelper.getLavaThreshold(biome);
            return (depth < lavaThresh && noiseVal < -0.5) ? Blocks.LAVA.getDefaultState() : null;
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
    public int getMinimumY() { return 0; }

    @Override
    public int getSeaLevel() { return 0; }

    @Override
    public int getWorldHeight() { return 16; }

    @Override
    public void populateEntities(ChunkRegion chunkRegion) {}

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        double planetR = QuadSphere.planetRadius();
        double distSq = (double) x * x + (double) z * z;
        if (distSq > planetR * planetR) return 0;
        double noiseVal = terrainNoise.GetNoise(x * NOISE_SCALE, 0, z * NOISE_SCALE);
        double y = Math.sqrt(planetR * planetR - distSq);
        return (int) Math.round(y + noiseVal * TERRAIN_AMPLITUDE);
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[world.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = Blocks.AIR.getDefaultState();
        return new VerticalBlockSample(world.getBottomY(), states);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() { return CODEC; }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
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

        double dip = BlockyPlanetConfig.horizonDipDegrees(planetRadius, 1.62);
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
            World w = BlockyPlanetMod.blockyWorld;
            if (w != null) {
                PlanetBlockStorage s = BlockyPlanetMod.getOrCreateStorage(w);
                text.add(String.format("§7Cubes in storage: §f%d", s.size()));
            }
        } catch (Exception e) {
            text.add("§7Addr: error");
        }
    }
}
