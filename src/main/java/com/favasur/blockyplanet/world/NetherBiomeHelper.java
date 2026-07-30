package com.favasur.blockyplanet.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

/**
 * Replicates the vanilla Nether biome distribution and block palettes
 * so the Nether ring inside the planet uses the same blocks as the
 * actual Nether dimension — not hand-rolled placeholders.
 *
 * Biomes are distributed via 3D noise (matching vanilla's approach):
 *
 *    Biome              |  noise range
 *    ───────────────────┼─────────────
 *    Soul Sand Valley   |  temp < -0.1
 *    Basalt Deltas      |  temp < 0.2 & erosion < 0.0
 *    Crimson Forest     |  weirdness > 0.1 & temp > 0.0
 *    Warped Forest      |  weirdness > 0.1 & temp < 0.0
 *    Nether Wastes      |  everything else
 */
public final class NetherBiomeHelper {

    private final FastNoiseLite temperatureNoise;
    private final FastNoiseLite weirdnessNoise;
    private final FastNoiseLite erosionNoise;

    /** Base surface radius — used to scale noise frequency with planet size. */
    private final double planetRadius;

    private static final double NOISE_FREQ = 0.008;

    public NetherBiomeHelper(double planetRadius) {
        this.planetRadius = planetRadius;

        temperatureNoise = new FastNoiseLite();
        temperatureNoise.SetSeed(100);
        temperatureNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        temperatureNoise.SetFrequency(NOISE_FREQ);
        temperatureNoise.SetFractalType(FastNoiseLite.FractalType.FBM);
        temperatureNoise.SetFractalOctaves(2);
        temperatureNoise.SetFractalLacunarity(2.0);
        temperatureNoise.SetFractalGain(0.5);

        weirdnessNoise = new FastNoiseLite();
        weirdnessNoise.SetSeed(101);
        weirdnessNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        weirdnessNoise.SetFrequency(NOISE_FREQ);
        weirdnessNoise.SetFractalType(FastNoiseLite.FractalType.FBM);
        weirdnessNoise.SetFractalOctaves(2);
        weirdnessNoise.SetFractalLacunarity(2.0);
        weirdnessNoise.SetFractalGain(0.5);

        erosionNoise = new FastNoiseLite();
        erosionNoise.SetSeed(102);
        erosionNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        erosionNoise.SetFrequency(NOISE_FREQ);
        erosionNoise.SetFractalType(FastNoiseLite.FractalType.FBM);
        erosionNoise.SetFractalOctaves(2);
        erosionNoise.SetFractalLacunarity(2.0);
        erosionNoise.SetFractalGain(0.5);
    }

    // ─── Biome enum matching vanilla Nether biome IDs ─────────────────────

    public enum NetherBiome {
        NETHER_WASTES,
        CRIMSON_FOREST,
        WARPED_FOREST,
        SOUL_SAND_VALLEY,
        BASALT_DELTAS
    }

    /**
     * Determine which Nether biome exists at the given position.
     * Uses the same 3D-noise-per-parameter approach as vanilla's
     * TheNetherBiomeSource but simplified for our spherical world.
     */
    public NetherBiome getBiome(double x, double y, double z) {
        // Scale positions so noise frequency is planet-size-invariant for large worlds
        double scale = Math.max(1.0, planetRadius / 1_000_000.0);

        double temp  = temperatureNoise.GetNoise(x * NOISE_FREQ / scale, y * NOISE_FREQ / scale, z * NOISE_FREQ / scale);
        double weird = weirdnessNoise.GetNoise( x * NOISE_FREQ / scale, y * NOISE_FREQ / scale, z * NOISE_FREQ / scale);
        double ero   = erosionNoise.GetNoise(   x * NOISE_FREQ / scale, y * NOISE_FREQ / scale, z * NOISE_FREQ / scale);

        // Vanilla thresholds (simplified)
        if (temp < -0.1) {
            return NetherBiome.SOUL_SAND_VALLEY;
        }
        if (temp < 0.2 && ero < 0.0) {
            return NetherBiome.BASALT_DELTAS;
        }
        if (weird > 0.1) {
            return temp > 0.0 ? NetherBiome.CRIMSON_FOREST : NetherBiome.WARPED_FOREST;
        }
        return NetherBiome.NETHER_WASTES;
    }

    // ─── Block palettes ───────────────────────────────────────────────────

    /**
     * Return the primary solid block for this biome (what fills underground).
     */
    public static BlockState getBaseBlock(NetherBiome biome) {
        return switch (biome) {
            case NETHER_WASTES, CRIMSON_FOREST, WARPED_FOREST -> Blocks.NETHERRACK.defaultBlockState();
            case SOUL_SAND_VALLEY -> Blocks.SOUL_SAND.defaultBlockState();
            case BASALT_DELTAS -> Blocks.BASALT.defaultBlockState();
        };
    }

    /**
     * Return the surface/top block for this biome (what appears at cave floors).
     */
    public static BlockState getTopBlock(NetherBiome biome) {
        return switch (biome) {
            case NETHER_WASTES      -> Blocks.NETHERRACK.defaultBlockState();
            case CRIMSON_FOREST     -> Blocks.CRIMSON_NYLIUM.defaultBlockState();
            case WARPED_FOREST      -> Blocks.WARPED_NYLIUM.defaultBlockState();
            case SOUL_SAND_VALLEY   -> Blocks.SOUL_SOIL.defaultBlockState();
            case BASALT_DELTAS      -> Blocks.BLACKSTONE.defaultBlockState();
        };
    }

    /**
     * Return the ceiling block for this biome (what appears on cave ceilings).
     */
    public static BlockState getCeilingBlock(NetherBiome biome) {
        return switch (biome) {
            case CRIMSON_FOREST -> Blocks.CRIMSON_NYLIUM.defaultBlockState();
            case WARPED_FOREST  -> Blocks.WARPED_NYLIUM.defaultBlockState();
            default             -> getBaseBlock(biome);
        };
    }

    /**
     * Return a decorative/scattered block for variety.
     * Returns null if nothing special should be placed.
     */
    public static BlockState getDecorationBlock(NetherBiome biome, double noiseValue) {
        return switch (biome) {
            case NETHER_WASTES -> {
                if (noiseValue > 0.85) yield Blocks.GRAVEL.defaultBlockState();
                if (noiseValue > 0.80) yield Blocks.GLOWSTONE.defaultBlockState();
                if (noiseValue < 0.10) yield Blocks.MAGMA_BLOCK.defaultBlockState();
                yield null;
            }
            case CRIMSON_FOREST -> {
                if (noiseValue > 0.80) yield Blocks.SHROOMLIGHT.defaultBlockState();
                if (noiseValue < 0.15) yield Blocks.NETHER_WART_BLOCK.defaultBlockState();
                yield null;
            }
            case WARPED_FOREST -> {
                if (noiseValue > 0.80) yield Blocks.SHROOMLIGHT.defaultBlockState();
                if (noiseValue < 0.15) yield Blocks.WARPED_WART_BLOCK.defaultBlockState();
                yield null;
            }
            case SOUL_SAND_VALLEY -> {
                if (noiseValue > 0.75) yield Blocks.SOUL_SOIL.defaultBlockState();
                if (noiseValue > 0.85) yield Blocks.BASALT.defaultBlockState();
                yield null;
            }
            case BASALT_DELTAS -> {
                if (noiseValue > 0.80) yield Blocks.MAGMA_BLOCK.defaultBlockState();
                if (noiseValue < 0.12) yield Blocks.GRAVEL.defaultBlockState();
                yield null;
            }
        };
    }

    /**
     * Whether this biome should hide cave walls (fully solid fill, no voids).
     */
    public static boolean isDenseBiome(NetherBiome biome) {
        return switch (biome) {
            case BASALT_DELTAS, SOUL_SAND_VALLEY -> false;  // caverns are open
            default -> true;                                 // forest/wastes have more solid ground
        };
    }

    /**
     * Lava threshold offset — biomes with higher values have less surface lava.
     */
    public static double getLavaThreshold(NetherBiome biome) {
        return switch (biome) {
            case BASALT_DELTAS  -> 0.30;
            case SOUL_SAND_VALLEY -> 0.25;
            default             -> 0.45;
        };
    }
}
