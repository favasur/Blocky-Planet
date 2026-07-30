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
 * Writes terrain into BOTH the vanilla Chunk (for within-world-height rendering)
 * AND the unbounded {@link PlanetBlockStorage} (for blocks at any Y).
 */
public class BlockyPlanetChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BlockyPlanetChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource)
        ).apply(instance, BlockyPlanetChunkGenerator::new)
    );

    private static final double TERRAIN_AMPLITUDE = 12.0;
    private static final double NOISE_SCALE = 0.03;

    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite biomeNoise;
    private final FastNoiseLite caveNoise;
    private final NetherBiomeHelper netherBiomeHelper;

    public BlockyPlanetChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
        this.terrainNoise = createTerrainNoise();
        this.biomeNoise = createBiomeNoise();
        this.caveNoise = createCaveNoise();
        this.netherBiomeHelper = new NetherBiomeHelper(QuadSphere.planetRadius());
    }

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

        // Get the PlanetBlockStorage from the static world reference
        PlanetBlockStorage storage = null;
        World world = BlockyPlanetMod.blockyWorld;
        if (world != null) {
            storage = BlockyPlanetMod.getOrCreateStorage(world);
        }

        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = chunkX * 16 + dx;
                int wz = chunkZ * 16 + dz;

                double xyDistSq = (double) wx * wx + (double) wz * wz;
                double maxYSq = maxReach * maxReach - xyDistSq;
                if (maxYSq < 0) continue;

                int yBound = (int) Math.floor(Math.sqrt(maxYSq));

                // Vanilla chunk bounds (for rendering)
                int startY = Math.max(-yBound, chunk.getBottomY());
                int endY   = Math.min(yBound, chunk.getTopY() - 1);

                // Full y-range (for PlanetBlockStorage — unbounded)
                int fullStartY = -yBound;
                int fullEndY   = yBound;

                for (int wy = fullStartY; wy <= fullEndY; wy++) {
                    double distFromCenter = Math.sqrt(xyDistSq + (double) wy * wy);
                    BlockState state = getGravityAlignedBlock(wx, wy, wz, distFromCenter);
                    if (state != null) {
                        // Always write to PlanetBlockStorage (unbounded Y)
                        if (storage != null) {
                            storage.setBlockState(wx, wy, wz, state);

                            // Compute surface normal from the already-computed
                            // BlockAddress for curved-block rendering.
                            BlockAddress addr = BlockAddress.fromWorldPosition(
                                new Vector3d(wx, wy, wz));
                            Vector3d normal = addr.getSurfaceNormal();
                            if (normal != null) {
                                storage.setNormal(wx, wy, wz, normal);
                            }
                        }
                        // Only write to vanilla chunk if within its height range
                        if (wy >= startY && wy <= endY) {
                            cursor.set(wx, wy, wz);
                            chunk.setBlockState(cursor, state, false);
                        }
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private BlockState getGravityAlignedBlock(int x, int y, int z, double distFromCenter) {
        Vector3d worldPos = new Vector3d(x, y, z);
        BlockAddress addr = BlockAddress.fromWorldPosition(worldPos);
        Vector3d alignedPos = addr.toWorldPositionImproved();
        double alignedDist = alignedPos.length();

        double surfaceRadius = getSurfaceRadius(alignedPos);
        double depthBelowSurface = surfaceRadius - alignedDist;

        if (depthBelowSurface < 0) {
            double waterRadius = QuadSphere.planetRadius() * 0.95;
            if (alignedDist <= waterRadius && alignedDist > QuadSphere.getShellInnerRadius(0)) {
                return Blocks.WATER.getDefaultState();
            }
            return null;
        }

        if (alignedDist < QuadSphere.getShellInnerRadius(0)) return null;
        double warpDist = worldPos.subtract(alignedPos).length();
        if (warpDist > 0.6) return getFallbackBlock(x, y, z, distFromCenter);

        boolean isArctic = isArcticRegion(alignedPos);
        if (depthBelowSurface <= 1.0) {
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
        } else if (depthBelowSurface <= 4.0) {
            if (alignedDist <= QuadSphere.planetRadius() * 0.97) return Blocks.SAND.getDefaultState();
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
        } else {
            if (BlockyPlanetConfig.isInNetherRing(alignedDist)) {
                return getNetherBlock(alignedPos, alignedDist, QuadSphere.planetRadius());
            }
            return Blocks.STONE.getDefaultState();
        }
    }

    private BlockState getFallbackBlock(int x, int y, int z, double distFromCenter) {
        double planetRadius = QuadSphere.planetRadius();
        if (distFromCenter > planetRadius + TERRAIN_AMPLITUDE) return null;
        if (distFromCenter < QuadSphere.getShellInnerRadius(0)) return null;
        double surfaceRadius = getSurfaceRadius(new Vector3d(x, y, z));
        double depth = surfaceRadius - distFromCenter;
        if (depth < 0) return null;
        if (depth <= 1.0) return Blocks.GRASS_BLOCK.getDefaultState();
        if (depth <= 4.0) {
            double sandRadius = planetRadius * 0.97;
            return distFromCenter <= sandRadius ? Blocks.SAND.getDefaultState() : Blocks.DIRT.getDefaultState();
        }
        if (BlockyPlanetConfig.isInNetherRing(distFromCenter)) {
            return getNetherBlock(new Vector3d(x, y, z), distFromCenter, planetRadius);
        }
        return Blocks.STONE.getDefaultState();
    }

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
        text.add("§6Blocky Planet (Cubic Mode)");
        text.add(String.format("§7Planet: §f%s ⌀  §7(%s radius)",
            BlockyPlanetConfig.formatDiameter((int) (planetRadius * 2)),
            BlockyPlanetConfig.formatRadius(planetRadius)));

        Vector3d v = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        double dist = v.length();
        double surfaceRadius = getSurfaceRadius(v);
        text.add(String.format("§7Surface: §f%.1f  §7Dist: §f%.1f  §7Depth: §f%.1f",
            surfaceRadius, dist, surfaceRadius - dist));

        double dip = BlockyPlanetConfig.horizonDipDegrees(planetRadius, 1.62);
        text.add(String.format("§7Horizon dip: §f%.4f°", dip));

        try {
            BlockAddress addr = BlockAddress.fromWorldPosition(v);
            Vector3d aligned = addr.toWorldPositionImproved();
            double offset = v.subtract(aligned).length();
            text.add(String.format("§7Addr: %s  §7offset: %.2f", addr, offset));
            // Show storage stats
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
