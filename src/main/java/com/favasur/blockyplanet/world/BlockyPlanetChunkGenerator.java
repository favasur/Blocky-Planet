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
import net.minecraft.world.biome.Biome;
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
import java.util.Random;
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
 *
 * Surface blocks are determined by the actual {@link BiomeSource} registered
 * for this dimension, making the planet compatible with any biome-adding mod
 * (Biomes O' Plenty, Terralith, etc.). Trees and surface features are placed
 * in {@link #populateEntities(ChunkRegion)}.
 */
public class BlockyPlanetChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BlockyPlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
        ).apply(instance, BlockyPlanetChunkGenerator::new)
    );

    private static final double TERRAIN_AMPLITUDE = 12.0;
    private static final double NOISE_SCALE = 0.03;
    private static final int SOIL_DEPTH = 4;

    // ─── Feature placement noise ─────────────────────────────────────────
    private static final long FEATURE_SEED_OFFSET = 12345L;

    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite biomeNoise;
    private final FastNoiseLite caveNoise;
    private final FastNoiseLite oreNoise;
    private final FastNoiseLite treeNoise;
    private final NetherBiomeHelper netherBiomeHelper;

    public BlockyPlanetChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
        this.terrainNoise = createTerrainNoise();
        this.biomeNoise = createBiomeNoise();
        this.caveNoise = createCaveNoise();
        this.oreNoise = createOreNoise();
        this.treeNoise = createTreeNoise();
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

    private static FastNoiseLite createTreeNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(555);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(0.05);
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

                // █ Query the biome from the chunk's already-set biome data █
                // This uses whatever biomes were set during createBiomes() —
                // including modded biomes from Biomes O' Plenty, Terralith, etc.
                // The coordinates are at noise-cell resolution (>> 2).
                // getBiomeForNoiseGen returns a RegistryEntry, so use .value().
                Biome columnBiome = null;
                try {
                    columnBiome = chunk.getBiomeForNoiseGen(wx >> 2, 0, wz >> 2).value();
                } catch (Exception e) {
                    // Silently fall back to noise-based biomes
                }

                for (int wy = startY; wy <= endY; wy++) {
                    double distFromCenter = Math.sqrt(xyDistSq + (double) wy * wy);
                    BlockState state = getGravityAlignedBlock(wx, wy, wz, distFromCenter, columnBiome);
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

    // ─── Entity spawning ─────────────────────────────────────────────────

    @Override
    public void populateEntities(ChunkRegion region) {
        // Place trees and surface features on each column within the region.
        // getCenterPos() returns a ChunkPos — convert to block start coords.
        var centerPos = region.getCenterPos();
        int chunkX = centerPos.x;
        int chunkZ = centerPos.z;

        Random random = new Random(region.getSeed());
        random.setSeed(random.nextLong() ^ (chunkX * 341873128712L + chunkZ * 132897987541L));

        placeSurfaceFeatures(region, chunkX << 4, chunkZ << 4, random);

        // Vanilla mob spawning runs automatically — no need to override with empty body.
    }

    /**
     * Place trees and other surface features on the planet's surface within
     * the given chunk region.
     */
    private void placeSurfaceFeatures(ChunkRegion region, int baseX, int baseZ, Random random) {
        double planetRadius = QuadSphere.planetRadius();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx;
                int wz = baseZ + dz;

                double distSq = (double) wx * wx + (double) wz * wz;
                if (distSq > planetRadius * planetRadius) continue;

                // Determine the surface Y at this column
                double y = Math.sqrt(planetRadius * planetRadius - distSq);
                double noiseVal = terrainNoise.GetNoise(wx * NOISE_SCALE, 0, wz * NOISE_SCALE);
                int surfaceY = (int) Math.round(y + noiseVal * TERRAIN_AMPLITUDE);

                // Check if the surface block is valid for feature placement
                BlockPos.Mutable pos = new BlockPos.Mutable(wx, surfaceY, wz);
                BlockState surfaceBlock = region.getBlockState(pos);
                if (surfaceBlock.isAir() || surfaceBlock.isOf(Blocks.WATER)) continue;

                // ✅ Tree placement — noise-based with ~3% density on grass
                double treeChance = treeNoise.GetNoise(wx * 1.0, 0, wz * 1.0) * 0.5 + 0.5;
                if (treeChance > 0.97 && surfaceBlock.isOf(Blocks.GRASS_BLOCK)) {
                    // Find tree height from noise (4-6 blocks)
                    int height = (int) (4 + (treeChance - 0.97) * 20);
                    height = Math.min(6, Math.max(3, height));
                    placeSimpleTree(region, region, wx, surfaceY + 1, wz, height, random);
                }

                // ✅ Tall grass on grass blocks
                if (treeChance > 0.70 && treeChance < 0.75 && surfaceBlock.isOf(Blocks.GRASS_BLOCK)) {
                    // Place tall grass is tricky without block entities — skip for now
                }
            }
        }
    }

    /**
     * Place a simple tree at the given position.
     * Trunk is oak log, canopy is oak leaves.
     */
    private void placeSimpleTree(ChunkRegion region, ChunkRegion chunkRegion,
                                  int x, int y, int z, int height, Random random) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        // Trunk
        for (int i = 0; i < height; i++) {
            pos.set(x, y + i, z);
            if (region.getBlockState(pos).isAir() || region.getBlockState(pos).isOf(Blocks.OAK_LEAVES)) {
                region.setBlockState(pos, Blocks.OAK_LOG.getDefaultState(), 3);
            }
        }

        // Canopy — simple 3x3 flat top with center
        int leafBaseY = y + height - 2;
        int leafTopY = y + height;

        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (lx == 0 && lz == 0) continue; // Don't replace trunk
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius && ly != leafTopY) {
                        // Skip corners on middle layers for a rounder look (50% chance)
                        if (random.nextBoolean()) continue;
                    }
                    pos.set(x + lx, ly, z + lz);
                    if (region.getBlockState(pos).isAir()) {
                        region.setBlockState(pos, Blocks.OAK_LEAVES.getDefaultState(), 3);
                    }
                }
            }
        }
    }

    // ─── Block selection ──────────────────────────────────────────────────

    /**
     * Determine what block to place at the given world position.
     * Accepts the column's biome from the actual BiomeSource for vanilla-
     * compatible surface generation.
     */
    private BlockState getGravityAlignedBlock(int x, int y, int z, double distFromCenter, Biome columnBiome) {
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
                return Blocks.WATER.getDefaultState();
            }
            return null;
        }

        // ──────── ⑥ Hollow core ────────────────────────────────────────────
        if (alignedDist < QuadSphere.getShellInnerRadius(0)) return null;

        double warpDist = worldPos.subtract(alignedPos).length();
        if (warpDist > 0.6) return getFallbackBlock(x, y, z, distFromCenter, columnBiome);

        // ──────── ④ Nether ring ────────────────────────────────────────────
        if (BlockyPlanetConfig.isInNetherRing(alignedDist)) {
            return getNetherBlock(alignedPos, alignedDist, planetRadius);
        }

        // ──────── ⑤ Lava layer ─────────────────────────────────────────────
        if (alignedDist < BlockyPlanetConfig.getNetherInnerRadius(planetRadius)) {
            return Blocks.LAVA.getDefaultState();
        }

        // ──────── ② + ③ Crust ──────────────────────────────────────────────
        if (depthBelowSurface <= 1.0) {
            return getSurfaceBlock(alignedPos, alignedDist, false, columnBiome);
        } else if (depthBelowSurface <= SOIL_DEPTH) {
            return getSubsurfaceBlock(alignedPos, alignedDist, columnBiome);
        } else {
            BlockState ore = getOreBlock(alignedPos, depthBelowSurface);
            return ore != null ? ore : Blocks.STONE.getDefaultState();
        }
    }

    // ─── Fallback ─────────────────────────────────────────────────────────

    private BlockState getFallbackBlock(int x, int y, int z, double distFromCenter, Biome columnBiome) {
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
            return Blocks.LAVA.getDefaultState();
        }
        if (depth <= SOIL_DEPTH) {
            Vector3d pos = new Vector3d(x, y, z);
            if (depth <= 1.0) return getSurfaceBlock(pos, distFromCenter, true, columnBiome);
            return getSubsurfaceBlock(pos, distFromCenter, columnBiome);
        }
        return Blocks.STONE.getDefaultState();
    }

    // ─── Vanilla BiomeSource surface blocks ──────────────────────────────

    /**
     * Return the top surface block, determined by the actual {@link Biome}
     * from the dimension's biome source.
     *
     * Uses the biome's precipitation and temperature to choose the right
     * vanilla-like block. This makes the planet surface match whatever
     * biomes are registered (including modded ones).
     */
    private BlockState getSurfaceBlock(Vector3d alignedPos, double alignedDist,
                                        boolean isFallback, Biome columnBiome) {
        double planetRadius = QuadSphere.planetRadius();
        double waterRadius = planetRadius * 0.95;

        // Underwater (ocean floor)
        if (alignedDist <= waterRadius) {
            return getUnderwaterSurfaceBlock(alignedPos, isFallback);
        }

        // Use the biome source if available
        if (columnBiome != null) {
            return getBiomeSurfaceBlock(columnBiome, alignedPos, alignedDist);
        }

        // Fallback: use noise-based classification
        if (isArcticRegion(alignedPos)) return Blocks.SNOW_BLOCK.getDefaultState();
        if (!isFallback && biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.25) {
            return Blocks.SAND.getDefaultState();
        }
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    /**
     * Query the actual biome's properties for the correct surface block.
     * Uses the biome's registry path for reliable pattern matching.
     * Falls back to noise-based classification for unknown biomes.
     */
    private BlockState getBiomeSurfaceBlock(Biome biome, Vector3d pos, double dist) {
        // Get the biome identifier from its string representation
        // Biome.toString() typically returns the registry path in 1.21
        String path = "";
        try {
            path = biome.toString().toLowerCase();
        } catch (Exception ignored) {}

        // Snowy biomes
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice")) {
            return Blocks.SNOW_BLOCK.getDefaultState();
        }

        // Taiga → podzol/grass
        if (path.contains("taiga")) {
            return Blocks.GRASS_BLOCK.getDefaultState();
        }

        // Desert / hot biomes
        if (path.contains("desert") || path.contains("badlands") || path.contains("savanna")) {
            return path.contains("badlands") ? Blocks.RED_SAND.getDefaultState() : Blocks.SAND.getDefaultState();
        }

        // Beach / shore
        if (path.contains("beach") || path.contains("shore") || path.contains("river")) {
            return Blocks.SAND.getDefaultState();
        }

        // Swamp
        if (path.contains("swamp") || path.contains("marsh")) {
            return Blocks.GRASS_BLOCK.getDefaultState();
        }

        // Mushroom fields
        if (path.contains("mushroom")) {
            return Blocks.MYCELIUM.getDefaultState();
        }

        // Default: grass
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    /**
     * Surface block for underwater positions (ocean floor).
     */
    private BlockState getUnderwaterSurfaceBlock(Vector3d alignedPos, boolean isFallback) {
        double gravelChance = isFallback ? 0.3 : biomeNoise.GetNoise(
            alignedPos.x() * 0.1, alignedPos.y() * 0.1, alignedPos.z() * 0.1);
        return gravelChance > 0.4 ? Blocks.GRAVEL.getDefaultState() : Blocks.SAND.getDefaultState();
    }

    /**
     * Subsurface block (depth 1-4). Uses biome info if available.
     */
    private BlockState getSubsurfaceBlock(Vector3d alignedPos, double alignedDist, Biome columnBiome) {
        double planetRadius = QuadSphere.planetRadius();
        double sandRadius = planetRadius * 0.97;

        // Use biome info if available
        if (columnBiome != null) {
            String biomeId = columnBiome.toString().toLowerCase();
            if (biomeId.contains("desert") || biomeId.contains("badlands")) {
                return biomeId.contains("badlands") ? Blocks.RED_SANDSTONE.getDefaultState() : Blocks.SANDSTONE.getDefaultState();
            }
        }

        // Noise-based fallback
        if (alignedDist <= sandRadius || !isArcticRegion(alignedPos)) {
            if (biomeNoise.GetNoise(alignedPos.x() * 0.04, alignedPos.y() * 0.04, alignedPos.z() * 0.04) > 0.2) {
                return Blocks.SANDSTONE.getDefaultState();
            }
        }
        return Blocks.DIRT.getDefaultState();
    }

    // ─── Ore generation ───────────────────────────────────────────────────

    private BlockState getOreBlock(Vector3d alignedPos, double depthBelowSurface) {
        double ore = oreNoise.GetNoise(alignedPos.x(), alignedPos.y(), alignedPos.z());

        if (ore > 0.70 && depthBelowSurface >= 5 && depthBelowSurface <= 100) return Blocks.COAL_ORE.getDefaultState();
        if (ore > 0.78 && depthBelowSurface >= 10 && depthBelowSurface <= 60) return Blocks.IRON_ORE.getDefaultState();
        if (ore > 0.82 && depthBelowSurface >= 15 && depthBelowSurface <= 50) return Blocks.COPPER_ORE.getDefaultState();
        if (ore > 0.88 && depthBelowSurface >= 20 && depthBelowSurface <= 40) return Blocks.GOLD_ORE.getDefaultState();
        if (ore > 0.90 && depthBelowSurface >= 20 && depthBelowSurface <= 30) return Blocks.REDSTONE_ORE.getDefaultState();
        if (ore > 0.93 && depthBelowSurface >= 20 && depthBelowSurface <= 35) return Blocks.LAPIS_ORE.getDefaultState();
        if (ore > 0.95 && depthBelowSurface >= 25 && depthBelowSurface <= 30) return Blocks.DIAMOND_ORE.getDefaultState();

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

        if (depth < 2.0 / thick || depth > 1.0 - 2.0 / thick) return Blocks.BEDROCK.getDefaultState();

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
