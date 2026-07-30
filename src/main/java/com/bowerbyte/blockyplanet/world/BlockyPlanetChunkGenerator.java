package com.bowerbyte.blockyplanet.world;

import com.bowerbyte.blockyplanet.BlockyPlanetMod;
import com.bowerbyte.blockyplanet.config.BlockyPlanetConfig;
import com.bowerbyte.blockyplanet.planet.BlockAddress;
import com.bowerbyte.blockyplanet.planet.QuadSphere;
import com.bowerbyte.blockyplanet.planet.Vector3d;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
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
 * Chunk generator that produces terrain on a spherical planet surface.
 *
 * Two-pass approach:
 * 1. Uses 3D noise to determine terrain height (as sphere radius variation)
 * 2. Places blocks at their gravity-aligned positions using the quad sphere
 *    mapping (BlockAddress), so block surfaces align with the planet's
 *    radial gravity direction.
 *
 * The vanilla chunk grid is overlayed on the spherical world. Each vanilla
 * chunk position corresponds to potentially multiple sectors/shells of the
 * quad sphere. Blocks are placed at world positions computed from their
 * BlockAddress, producing the correct gravity-aligned orientation.
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

    /** Noise for the Nether ring cave system. */
    private final FastNoiseLite netherCaveNoise;
    /** Noise for Nether lava pockets. */
    private final FastNoiseLite netherLavaNoise;

    public BlockyPlanetChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
        this.terrainNoise = createTerrainNoise();
        this.biomeNoise = createBiomeNoise();
        this.netherCaveNoise = createNetherCaveNoise();
        this.netherLavaNoise = createNetherLavaNoise();
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

    private static FastNoiseLite createNetherCaveNoise() {
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

    private static FastNoiseLite createNetherLavaNoise() {
        FastNoiseLite noise = new FastNoiseLite();
        noise.SetSeed(667);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        noise.SetFrequency(0.04);
        noise.SetFractalType(FastNoiseLite.FractalType.None);
        return noise;
    }

    /**
     * Generate Nether block at a position within the Nether ring.
     */
    private BlockState getNetherBlock(Vector3d pos, double distFromCenter, double planetRadius) {
        // Cave noise: positive → solid (netherrack), negative → air (cave)
        double caveNoise = netherCaveNoise.GetNoise(pos.x() * 0.02, pos.y() * 0.02, pos.z() * 0.02);

        // Threshhold: ~75% of noise range is solid, 25% is cave
        boolean isCave = caveNoise < -0.3;

        if (isCave) {
            // Check for lava at the bottom of the cave
            // Lava forms in a band near the bottom 40% of the Nether ring
            double netherInner = BlockyPlanetConfig.getNetherInnerRadius(planetRadius);
            double netherOuter = BlockyPlanetConfig.getNetherOuterRadius(planetRadius);
            double ringThickness = netherOuter - netherInner;
            double depthInRing = distFromCenter - netherInner; // 0 at inner edge
            double normalizedDepth = depthInRing / ringThickness; // 0=inner, 1=outer

            // Lava in the lower 40% of the ring, with some noise variation
            double lavaNoise = netherLavaNoise.GetNoise(pos.x() * 0.03, pos.y() * 0.03, pos.z() * 0.03);
            double lavaThreshold = 0.4 + lavaNoise * 0.2;

            if (normalizedDepth < lavaThreshold && caveNoise < -0.5) {
                return Blocks.LAVA.getDefaultState();
            }
            return null; // Air
        }

        // ─── Solid Nether blocks ────────────────────────────────────────
        // At the very top and bottom of the ring, use bedrock as a "ceiling/floor"
        double netherInner = BlockyPlanetConfig.getNetherInnerRadius(planetRadius);
        double netherOuter = BlockyPlanetConfig.getNetherOuterRadius(planetRadius);
        double ringThickness = netherOuter - netherInner;
        double depthInRing = distFromCenter - netherInner;
        double normalizedDepth = depthInRing / ringThickness;

        // Bedrock at top (closer to surface) and bottom (closer to core)
        double bedrockOuterBand = 2.0 / ringThickness; // 2 blocks at outer edge
        double bedrockInnerBand = 2.0 / ringThickness; // 2 blocks at inner edge

        if (normalizedDepth > 1.0 - bedrockOuterBand || normalizedDepth < bedrockInnerBand) {
            return Blocks.BEDROCK.getDefaultState();
        }

        // Netherrack with some variety
        double variety = caveNoise * 0.5 + 0.5; // 0..1
        if (variety < 0.15) {
            return Blocks.GRAVEL.getDefaultState();
        } else if (variety > 0.85) {
            return Blocks.SOUL_SAND.getDefaultState();
        } else if (variety > 0.7 && variety < 0.75) {
            return Blocks.GLOWSTONE.getDefaultState();
        }
        return Blocks.NETHERRACK.getDefaultState();
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk, GenerationStep.Carver carverStep) {
        // No carving
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        // Handled in populateNoise
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dy = chunk.getBottomY(); dy < chunk.getTopY(); dy++) {
                    int wx = chunkX * 16 + dx;
                    int wy = dy;
                    int wz = chunkZ * 16 + dz;

                    double distFromCenter = Math.sqrt(
                        (double) wx * wx + (double) wy * wy + (double) wz * wz
                    );
                    double planetRadius = QuadSphere.planetRadius();
                    if (distFromCenter > planetRadius + TERRAIN_AMPLITUDE) {
                        continue;
                    }

                    // Place block using the gravity-aligned quad sphere mapping
                    BlockState state = getGravityAlignedBlock(wx, wy, wz, distFromCenter);
                    if (state != null) {
                        pos.set(wx, wy, wz);
                        chunk.setBlockState(pos, state, false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * Get the block type at a world position using the quad sphere mapping.
     *
     * Uses the BlockAddress system to determine which sector/shell/chunk/block
     * this position corresponds to, then places terrain based on 3D noise
     * sampled at the gravity-aligned position.
     *
     * This ensures block faces align with the planet's radial gravity direction,
     * matching the blog post's "gravity-aligned blocks" approach.
     */
    private BlockState getGravityAlignedBlock(int x, int y, int z, double distFromCenter) {
        Vector3d worldPos = new Vector3d(x, y, z);

        // Get the gravity-aligned position from the quad sphere
        BlockAddress addr = BlockAddress.fromWorldPosition(worldPos);
        Vector3d alignedPos = addr.toWorldPositionImproved();
        double alignedDist = alignedPos.length();

        // Sample 3D noise at the gravity-aligned position for terrain generation
        double surfaceRadius = getSurfaceRadius(alignedPos);

        double depthBelowSurface = surfaceRadius - alignedDist;

        if (depthBelowSurface < 0) {
            double waterRadius = QuadSphere.planetRadius() * 0.95;
            if (alignedDist <= waterRadius) {
                return Blocks.WATER.getDefaultState();
            }
            return null; // Air
        }

        // Check if we should place a block at this world position
        // by comparing the gravity-aligned position to the actual world position
        double warpDist = worldPos.subtract(alignedPos).length();
        if (warpDist > 0.5) {
            return getFallbackBlock(x, y, z, distFromCenter);
        }

        boolean isArctic = isArcticRegion(alignedPos);

        if (depthBelowSurface <= 1.0) {
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
        } else if (depthBelowSurface <= 4.0) {
            double sandRadius = QuadSphere.planetRadius() * 0.97;
            if (alignedDist <= sandRadius) {
                return Blocks.SAND.getDefaultState();
            }
            return isArctic ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.DIRT.getDefaultState();
        } else {
            // Check Nether ring
            double planetRadius = QuadSphere.planetRadius();
            if (BlockyPlanetConfig.isInNetherRing(alignedDist)) {
                return getNetherBlock(alignedPos, alignedDist, planetRadius);
            }
            return Blocks.STONE.getDefaultState();
        }
    }

    /**
     * Fallback for positions near sector boundaries where the gravity-aligned
     * mapping may leave gaps. Uses the simple distance-based approach.
     */
    private BlockState getFallbackBlock(int x, int y, int z, double distFromCenter) {
        double planetRadius = QuadSphere.planetRadius();
        if (distFromCenter > planetRadius + TERRAIN_AMPLITUDE) return null;
        if (distFromCenter < QuadSphere.getShellInnerRadius(0)) return Blocks.STONE.getDefaultState();

        double surfaceRadius = getSurfaceRadius(new Vector3d(x, y, z));
        double depth = surfaceRadius - distFromCenter;

        if (depth < 0) {
            double waterRadius = planetRadius * 0.95;
            return distFromCenter <= waterRadius ? Blocks.WATER.getDefaultState() : null;
        }

        if (depth <= 1.0) return Blocks.GRASS_BLOCK.getDefaultState();
        if (depth <= 4.0) {
            double sandRadius = planetRadius * 0.97;
            return distFromCenter <= sandRadius ? Blocks.SAND.getDefaultState() : Blocks.DIRT.getDefaultState();
        }
        // Check Nether ring
        if (BlockyPlanetConfig.isInNetherRing(distFromCenter)) {
            return getNetherBlock(new Vector3d(x, y, z), distFromCenter, planetRadius);
        }
        return Blocks.STONE.getDefaultState();
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

        double noiseVal = biomeNoise.GetNoise(pos.x() * 0.01 * 10, pos.y() * 0.01 * 10, pos.z() * 0.01 * 10);
        double threshold = 0.5 + noiseVal * 0.15;

        return polarAngle < threshold;
    }

    @Override
    public int getMinimumY() {
        return -64;
    }

    @Override
    public int getSeaLevel() {
        return (int) (QuadSphere.planetRadius() * 0.95);
    }

    @Override
    public int getWorldHeight() {
        return 384;
    }

    @Override
    public void populateEntities(ChunkRegion chunkRegion) {
        // No custom entity spawning
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return (int) Math.round(QuadSphere.planetRadius());
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[world.getHeight()];
        for (int i = 0; i < states.length; i++) {
            states[i] = Blocks.AIR.getDefaultState();
        }
        return new VerticalBlockSample(world.getBottomY(), states);
    }

    @Override
    public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        double planetRadius = QuadSphere.planetRadius();
        double planetDiameter = planetRadius * 2;
        text.add("§6Blocky Planet Generator");
        text.add(String.format("§7Planet: §f%s ⌀  §7(%s radius)",
            BlockyPlanetConfig.formatDiameter((int) planetDiameter),
            BlockyPlanetConfig.formatRadius(planetRadius)));

        Vector3d v = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        double dist = v.length();
        double surfaceRadius = getSurfaceRadius(v);
        text.add(String.format("§7Surface: §f%.1f  §7Dist: §f%.1f  §7Depth: §f%.1f", surfaceRadius, dist, surfaceRadius - dist));

        double dip = BlockyPlanetConfig.horizonDipDegrees(planetRadius, 1.62);
        double horizDist = BlockyPlanetConfig.horizonDistance(planetRadius, 1.62);
        text.add(String.format("§7Horizon dip: §f%.4f°  §7Horizon dist: §f%s",
            dip, BlockyPlanetConfig.formatDiameter((int) horizDist)));

        // Nether ring info
        boolean inNether = BlockyPlanetConfig.isInNetherRing(dist);
        double netherInnerDepth = BlockyPlanetConfig.getNetherInnerDepth(planetRadius);
        double netherOuterDepth = BlockyPlanetConfig.getNetherOuterDepth(planetRadius);
        String netherStatus = inNether ? "§cINSIDE" : "§8outside";
        text.add(String.format("§7Nether ring: %s  §7(inner: §f%s  §7outer: §f%s)",
            netherStatus,
            BlockyPlanetConfig.formatDiameter((int) netherInnerDepth),
            BlockyPlanetConfig.formatDiameter((int) netherOuterDepth)));

        try {
            BlockAddress addr = BlockAddress.fromWorldPosition(v);
            Vector3d aligned = addr.toWorldPositionImproved();
            double offset = v.subtract(aligned).length();
            text.add(String.format("§7Addr: %s", addr));
            text.add(String.format("§7Aligned offset: %.2f", offset));
        } catch (Exception e) {
            text.add("§7Addr: error");
        }
    }
}
