package com.bowerbyte.blockyplanet.world.cube;

import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;
import com.bowerbyte.blockyplanet.planet.BlockAddress;
import com.bowerbyte.blockyplanet.planet.QuadSphere;
import com.bowerbyte.blockyplanet.planet.Vector3d;
import com.bowerbyte.blockyplanet.world.FastNoiseLite;
import com.bowerbyte.blockyplanet.world.NetherBiomeHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Generates 16×16×16 cubes of block data for the quad-sphere planet.
 *
 * Each cube is identified by its world-space cube position (cx, cy, cz).
 * The generator uses the quad-sphere {@link BlockAddress} system to
 * map world positions to gravity-aligned terrain positions, ensuring
 * blocks face the correct radial direction.
 *
 * This is called on-demand when the renderer needs a section that
 * hasn't been generated yet.
 */
public class CubeGenerator {

    private static final double TERRAIN_AMPLITUDE = 12.0;
    private static final double NOISE_SCALE = 0.03;

    private static final FastNoiseLite terrainNoise = createTerrainNoise();
    private static final FastNoiseLite biomeNoise = createBiomeNoise();
    private static final FastNoiseLite caveNoise = createCaveNoise();
    private static final NetherBiomeHelper netherBiomeHelper = new NetherBiomeHelper(QuadSphere.planetRadius());

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

    /**
     * Generate all 4096 blocks for a cube and write them into the storage.
     *
     * @param storage The storage to write into
     * @param cx Cube X (world coordinate >> 4)
     * @param cy Cube Y (world coordinate >> 4)
     * @param cz Cube Z (world coordinate >> 4)
     */
    public static void generateCube(PlanetBlockStorage storage, int cx, int cy, int cz) {
        int baseX = cx << 4;
        int baseY = cy << 4;
        int baseZ = cz << 4;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx;
                int wz = baseZ + dz;

                for (int dy = 0; dy < 16; dy++) {
                    int wy = baseY + dy;

                    double distFromCenter = Math.sqrt(
                        (double) wx * wx + (double) wy * wy + (double) wz * wz
                    );

                    BlockState state = getBlock(wx, wy, wz, distFromCenter);
                    if (state != null) {
                        storage.setBlockState(wx, wy, wz, state);
                    }
                }
            }
        }
    }

    /**
     * Get the block at a world position using the quad-sphere mapping.
     * Returns null for air.
     */
    private static BlockState getBlock(int x, int y, int z, double distFromCenter) {
        double planetRadius = QuadSphere.planetRadius();
        double maxReach = planetRadius + TERRAIN_AMPLITUDE;

        if (distFromCenter > maxReach) return null;
        if (distFromCenter < QuadSphere.getShellInnerRadius(0)) return null;

        Vector3d worldPos = new Vector3d(x, y, z);
        BlockAddress addr = BlockAddress.fromWorldPosition(worldPos);
        Vector3d alignedPos = addr.toWorldPositionImproved();
        double alignedDist = alignedPos.length();

        double surfaceRadius = getSurfaceRadius(alignedPos);
        double depthBelowSurface = surfaceRadius - alignedDist;

        if (depthBelowSurface < 0) {
            double waterRadius = planetRadius * 0.95;
            if (alignedDist <= waterRadius && alignedDist > QuadSphere.getShellInnerRadius(0)) {
                return Blocks.WATER.getDefaultState();
            }
            return null;
        }

        double warpDist = worldPos.subtract(alignedPos).length();
        if (warpDist > 0.6) return getFallbackBlock(x, y, z, distFromCenter);

        boolean isArctic = isArcticRegion(alignedPos);

        if (depthBelowSurface <= 1.0) {
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
        } else if (depthBelowSurface <= 4.0) {
            if (alignedDist <= planetRadius * 0.97) return Blocks.SAND.getDefaultState();
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
        } else {
            if (BlockyPlanetConfig.isInNetherRing(alignedDist)) {
                return getNetherBlock(alignedPos, alignedDist, planetRadius);
            }
            return Blocks.STONE.getDefaultState();
        }
    }

    private static BlockState getFallbackBlock(int x, int y, int z, double distFromCenter) {
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

    private static BlockState getNetherBlock(Vector3d pos, double distFromCenter, double planetRadius) {
        double px = pos.x(), py = pos.y(), pz = pos.z();
        NetherBiomeHelper.NetherBiome biome = netherBiomeHelper.getBiome(px, py, pz);
        double noiseVal = caveNoise.GetNoise(px * 0.02, py * 0.02, pz * 0.02);

        double inner  = BlockyPlanetConfig.getNetherInnerRadius(planetRadius);
        double outer  = BlockyPlanetConfig.getNetherOuterRadius(planetRadius);
        double thick  = outer - inner;
        double depth  = (distFromCenter - inner) / thick;

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

    private static boolean isNearCave(double x, double y, double z) {
        double center = caveNoise.GetNoise(x * 0.02, y * 0.02, z * 0.02);
        if (center < -0.3) return true;
        return caveNoise.GetNoise((x + 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise((x - 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y + 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y - 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z + 1) * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z - 1) * 0.02) < -0.3;
    }

    private static double getSurfaceRadius(Vector3d pos) {
        double noiseVal = terrainNoise.GetNoise(pos.x() * NOISE_SCALE, pos.y() * NOISE_SCALE, pos.z() * NOISE_SCALE);
        return QuadSphere.planetRadius() + noiseVal * TERRAIN_AMPLITUDE;
    }

    private static boolean isArcticRegion(Vector3d pos) {
        double dist = pos.length();
        if (dist < 1) return false;
        double ny = pos.y() / dist;
        double polarAngle = Math.acos(Math.abs(ny));
        double noiseVal = biomeNoise.GetNoise(pos.x() * 0.1, pos.y() * 0.1, pos.z() * 0.1);
        double threshold = 0.5 + noiseVal * 0.15;
        return polarAngle < threshold;
    }
}
