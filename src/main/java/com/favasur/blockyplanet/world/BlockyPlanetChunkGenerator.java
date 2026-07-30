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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk generator for the spherical Blocky Planet world (NeoForge).
 *
 * Layer structure: surface → subsurface → stone crust → nether ring → lava → core.
 * Surface blocks use the actual BiomeSource for vanilla/modded biome compatibility.
 * Includes tree generation in populateEntities, ore veins in crust, lava below Nether.
 * When Tellus mod is detected, surface blocks are read from the overworld via projection.
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

    private static FastNoiseLite createTerrainNoise() {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed(42); n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        n.SetFrequency(NOISE_SCALE); n.SetFractalType(FastNoiseLite.FractalType.FBM);
        n.SetFractalOctaves(3); n.SetFractalLacunarity(2.0); n.SetFractalGain(0.5);
        return n;
    }

    private static FastNoiseLite createBiomeNoise() {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed(99); n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        n.SetFrequency(0.01); return n;
    }

    private static FastNoiseLite createCaveNoise() {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed(666); n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        n.SetFrequency(0.02); n.SetFractalType(FastNoiseLite.FractalType.FBM);
        n.SetFractalOctaves(2); n.SetFractalLacunarity(2.0); n.SetFractalGain(0.5);
        return n;
    }

    private static FastNoiseLite createOreNoise() {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed(999); n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        n.SetFrequency(0.08); n.SetFractalType(FastNoiseLite.FractalType.None);
        return n;
    }

    private static FastNoiseLite createTreeNoise() {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed(555); n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2S);
        n.SetFrequency(0.05); n.SetFractalType(FastNoiseLite.FractalType.None);
        return n;
    }

    @Override public void applyCarvers(WorldGenRegion r, long s, RandomState rs, BiomeManager bm, StructureManager sm, ChunkAccess c, GenerationStep.Carving cv) {}
    @Override public void buildSurface(WorldGenRegion r, StructureManager sm, RandomState rs, ChunkAccess c) {}

    // ─── Noise population (terrain) ──────────────────────────────────────

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random,
                                                          StructureManager structureManager, ChunkAccess chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        double planetRadius = QuadSphere.planetRadius();
        double maxReach = planetRadius + TERRAIN_AMPLITUDE;

        PlanetBlockStorage storage = null;
        Level world = BlockyPlanetMod.blockyWorld;
        if (world != null) storage = BlockyPlanetMod.getOrCreateStorage(world);

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

                // Query biome from the chunk's noise-cell biome data
                Biome columnBiome = null;
                try {
                    columnBiome = chunk.getNoiseBiome(wx >> 2, 0, wz >> 2).value();
                } catch (Exception ignored) {}

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

    // ─── Trees & mobs ────────────────────────────────────────────────────

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        var centerPos = region.getCenter();
        int chunkX = centerPos.x;
        int chunkZ = centerPos.z;

        Random random = new Random(region.getSeed());
        random.setSeed(random.nextLong() ^ (chunkX * 341873128712L + chunkZ * 132897987541L));

        placeSurfaceFeatures(region, chunkX << 4, chunkZ << 4, random);
    }

    private void placeSurfaceFeatures(WorldGenRegion region, int baseX, int baseZ, Random random) {
        double planetRadius = QuadSphere.planetRadius();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int wx = baseX + dx, wz = baseZ + dz;
                double distSq = (double) wx * wx + (double) wz * wz;
                if (distSq > planetRadius * planetRadius) continue;

                double y = Math.sqrt(planetRadius * planetRadius - distSq);
                double noiseVal = terrainNoise.GetNoise(wx * NOISE_SCALE, 0, wz * NOISE_SCALE);
                int surfaceY = (int) Math.round(y + noiseVal * TERRAIN_AMPLITUDE);

                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(wx, surfaceY, wz);
                BlockState surfaceBlock = region.getBlockState(pos);
                if (surfaceBlock.isAir() || surfaceBlock.is(Blocks.WATER)) continue;

                double treeChance = treeNoise.GetNoise(wx * 1.0, 0, wz * 1.0) * 0.5 + 0.5;
                if (treeChance > 0.97 && surfaceBlock.is(Blocks.GRASS_BLOCK)) {
                    int height = Math.min(6, Math.max(3, (int) (4 + (treeChance - 0.97) * 20)));
                    placeSimpleTree(region, wx, surfaceY + 1, wz, height, random);
                }
            }
        }
    }

    private void placeSimpleTree(WorldGenRegion region, int x, int y, int z, int height, Random random) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < height; i++) {
            pos.set(x, y + i, z);
            if (region.getBlockState(pos).isAir() || region.getBlockState(pos).is(Blocks.OAK_LEAVES))
                region.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        int leafBaseY = y + height - 2;
        int leafTopY = y + height;
        for (int ly = leafBaseY; ly <= leafTopY; ly++) {
            int radius = (ly == leafTopY) ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
                for (int lz = -radius; lz <= radius; lz++) {
                    if (lx == 0 && lz == 0) continue;
                    if (Math.abs(lx) == radius && Math.abs(lz) == radius && ly != leafTopY && random.nextBoolean()) continue;
                    pos.set(x + lx, ly, z + lz);
                    if (region.getBlockState(pos).isAir())
                        region.setBlock(pos, Blocks.OAK_LEAVES.defaultBlockState(), 2);
                }
            }
        }
    }

    // ─── Block selection ──────────────────────────────────────────────────

    private BlockState getGravityAlignedBlock(int x, int y, int z, double dist, Biome biome) {
        Vector3d worldPos = new Vector3d(x, y, z);
        BlockAddress addr = BlockAddress.fromWorldPosition(worldPos);
        Vector3d ap = addr.toWorldPositionImproved();
        double ad = ap.length();

        double pr = QuadSphere.planetRadius();
        double sr = getSurfaceRadius(ap);
        double d = sr - ad;

        if (d < 0) {
            double wr = pr * 0.95;
            if (ad <= wr && ad > QuadSphere.getShellInnerRadius(0)) return Blocks.WATER.defaultBlockState();
            return null;
        }
        if (ad < QuadSphere.getShellInnerRadius(0)) return null;
        double warp = worldPos.subtract(ap).length();
        if (warp > 0.6) return getFallbackBlock(x, y, z, dist, biome);
        if (BlockyPlanetConfig.isInNetherRing(ad)) return getNetherBlock(ap, ad, pr);
        if (ad < BlockyPlanetConfig.getNetherInnerRadius(pr)) return Blocks.LAVA.defaultBlockState();

        if (d <= 1.0) return getSurfaceBlock(ap, ad, false, biome);
        if (d <= SOIL_DEPTH) return getSubsurfaceBlock(ap, ad, biome);
        BlockState ore = getOreBlock(ap, d);
        return ore != null ? ore : Blocks.STONE.defaultBlockState();
    }

    private BlockState getFallbackBlock(int x, int y, int z, double dist, Biome biome) {
        double pr = QuadSphere.planetRadius();
        if (dist > pr + TERRAIN_AMPLITUDE || dist < QuadSphere.getShellInnerRadius(0)) return null;
        double sr = getSurfaceRadius(new Vector3d(x, y, z));
        double d = sr - dist;
        if (d < 0) return null;
        if (BlockyPlanetConfig.isInNetherRing(dist)) return getNetherBlock(new Vector3d(x, y, z), dist, pr);
        if (dist < BlockyPlanetConfig.getNetherInnerRadius(pr)) return Blocks.LAVA.defaultBlockState();
        if (d <= SOIL_DEPTH) return d <= 1.0 ? getSurfaceBlock(new Vector3d(x, y, z), dist, true, biome) : getSubsurfaceBlock(new Vector3d(x, y, z), dist, biome);
        return Blocks.STONE.defaultBlockState();
    }

    // ─── Surface blocks (BiomeSource integrated + Tellus) ────────────────

    private BlockState getSurfaceBlock(Vector3d p, double ad, boolean fallback, Biome biome) {
        double pr = QuadSphere.planetRadius();
        double wr = pr * 0.95;

        // ── When Tellus is loaded, read surface blocks from the overworld ──
        if (BlockyPlanetMod.TELLUS_LOADED && BlockyPlanetMod.tellusOverworld != null) {
            BlockState tellusBlock = getTellusSurfaceBlock(p);
            if (tellusBlock != null) return tellusBlock;
        }

        if (ad <= wr) {
            double gc = fallback ? 0.3 : biomeNoise.GetNoise(p.x() * 0.1, p.y() * 0.1, p.z() * 0.1);
            return gc > 0.4 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        }
        if (biome != null) return getBiomeSurfaceBlock(biome);
        if (isArcticRegion(p)) return Blocks.SNOW_BLOCK.defaultBlockState();
        if (!fallback && biomeNoise.GetNoise(p.x() * 0.04, p.y() * 0.04, p.z() * 0.04) > 0.25)
            return Blocks.SAND.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /**
     * Read the ACTUAL surface block from the Tellus overworld via equirectangular
     * projection. Projects the planet's spherical position to flat overworld
     * coordinates, reads the topmost non-air block at that position from
     * the Tellus-generated chunk, and returns it directly.
     *
     * This gives the player Tellus's actual Earth terrain blocks (grass,
     * stone, sand, etc.) wrapped around the spherical planet.
     *
     * Falls back to our biome-mapped surface on any error (chunk not loaded,
     * Tellus not available, etc.).
     */
    private BlockState getTellusSurfaceBlock(Vector3d alignedPos) {
        try {
            Level overworld = BlockyPlanetMod.tellusOverworld;
            if (overworld == null) return null;

            // Surface normal direction from planet center (unit vector)
            double dist = alignedPos.length();
            if (dist < 1) return null;
            double nx = alignedPos.x() / dist;
            double ny = alignedPos.y() / dist;
            double nz = alignedPos.z() / dist;

            // Spherical coordinates
            double lat = Math.asin(ny);               // -π/2 to π/2
            double lon = Math.atan2(nz, nx);           // -π to π

            // Equirectangular projection to overworld flat coordinates
            double scale = QuadSphere.planetRadius();
            int ox = (int) Math.round(lon * scale);
            int oz = (int) Math.round(lat * scale);

            // Check if the Tellus chunk is loaded — if not, skip to avoid
            // forcing chunk loads from a worker thread.
            int chunkX = ox >> 4;
            int chunkZ = oz >> 4;
            if (!overworld.hasChunk(chunkX, chunkZ)) {
                return null;
            }

            // Get the chunk and read the topmost non-air surface block
            LevelChunk tellusChunk = overworld.getChunk(chunkX, chunkZ);
            int topY = tellusChunk.getHeight(Heightmap.Types.WORLD_SURFACE, ox, oz);
            BlockPos surfacePos = new BlockPos(ox, topY, oz);
            BlockState tellusBlock = overworld.getBlockState(surfacePos);

            // If we got a valid non-air block, use it directly
            if (tellusBlock != null && !tellusBlock.isAir() && tellusBlock.getBlock() != net.minecraft.world.level.block.Blocks.VOID_AIR) {
                return tellusBlock;
            }

            // Fallback: use the Tellus biome to determine surface block type
            Biome tellusBiome = overworld.getBiome(surfacePos).value();
            if (tellusBiome != null) {
                return getBiomeSurfaceBlock(tellusBiome);
            }

            return null;
        } catch (Exception e) {
            return null; // Silently fall back to our generation
        }
    }

    private BlockState getBiomeSurfaceBlock(Biome biome) {
        String path = getBiomePath(biome);
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice"))
            return Blocks.SNOW_BLOCK.defaultBlockState();
        if (path.contains("desert") || path.contains("badlands") || path.contains("savanna"))
            return path.contains("badlands") ? Blocks.RED_SAND.defaultBlockState() : Blocks.SAND.defaultBlockState();
        if (path.contains("beach") || path.contains("shore") || path.contains("river"))
            return Blocks.SAND.defaultBlockState();
        if (path.contains("swamp") || path.contains("mushroom"))
            return path.contains("mushroom") ? Blocks.MYCELIUM.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    /**
     * Extract the biome identifier path for pattern matching.
     * Uses toString() which returns the registry path on Mojmap (NeoForge).
     */
    private String getBiomePath(Biome biome) {
        try {
            return biome.toString().toLowerCase();
        } catch (Exception ignored) {}
        return "";
    }

    private BlockState getSubsurfaceBlock(Vector3d p, double ad, Biome biome) {
        double pr = QuadSphere.planetRadius();
        double sr = pr * 0.97;
        if (biome != null) {
            String path = getBiomePath(biome);
            if (path.contains("desert")) return Blocks.SANDSTONE.defaultBlockState();
            if (path.contains("badlands")) return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        if (ad <= sr || !isArcticRegion(p)) {
            if (biomeNoise.GetNoise(p.x() * 0.04, p.y() * 0.04, p.z() * 0.04) > 0.2)
                return Blocks.SANDSTONE.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    // ─── Ores ────────────────────────────────────────────────────────────

    private BlockState getOreBlock(Vector3d p, double depth) {
        double ore = oreNoise.GetNoise(p.x(), p.y(), p.z());
        if (ore > 0.70 && depth >= 5 && depth <= 100) return Blocks.COAL_ORE.defaultBlockState();
        if (ore > 0.78 && depth >= 10 && depth <= 60) return Blocks.IRON_ORE.defaultBlockState();
        if (ore > 0.82 && depth >= 15 && depth <= 50) return Blocks.COPPER_ORE.defaultBlockState();
        if (ore > 0.88 && depth >= 20 && depth <= 40) return Blocks.GOLD_ORE.defaultBlockState();
        if (ore > 0.90 && depth >= 20 && depth <= 30) return Blocks.REDSTONE_ORE.defaultBlockState();
        if (ore > 0.93 && depth >= 20 && depth <= 35) return Blocks.LAPIS_ORE.defaultBlockState();
        if (ore > 0.95 && depth >= 25 && depth <= 30) return Blocks.DIAMOND_ORE.defaultBlockState();
        return null;
    }

    // ─── Nether ring ──────────────────────────────────────────────────────

    private BlockState getNetherBlock(Vector3d pos, double dist, double pr) {
        double px = pos.x(), py = pos.y(), pz = pos.z();
        var biome = netherBiomeHelper.getBiome(px, py, pz);
        double nv = caveNoise.GetNoise(px * 0.02, py * 0.02, pz * 0.02);
        double inner = BlockyPlanetConfig.getNetherInnerRadius(pr);
        double outer = BlockyPlanetConfig.getNetherOuterRadius(pr);
        double thick = outer - inner;
        double depth = (dist - inner) / thick;
        if (depth < 2.0 / thick || depth > 1.0 - 2.0 / thick) return Blocks.BEDROCK.defaultBlockState();
        double ct = NetherBiomeHelper.isDenseBiome(biome) ? -0.1 : -0.4;
        if (nv < ct) {
            double lt = NetherBiomeHelper.getLavaThreshold(biome);
            return (depth < lt && nv < -0.5) ? Blocks.LAVA.defaultBlockState() : null;
        }
        boolean nc = isNearCave(px, py, pz);
        if (nc && depth > 0.5) return NetherBiomeHelper.getCeilingBlock(biome);
        if (nc) return NetherBiomeHelper.getTopBlock(biome);
        BlockState decor = NetherBiomeHelper.getDecorationBlock(biome, nv);
        return decor != null ? decor : NetherBiomeHelper.getBaseBlock(biome);
    }

    private boolean isNearCave(double x, double y, double z) {
        double c = caveNoise.GetNoise(x * 0.02, y * 0.02, z * 0.02);
        if (c < -0.3) return true;
        return caveNoise.GetNoise((x + 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise((x - 1) * 0.02, y * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y + 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, (y - 1) * 0.02, z * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z + 1) * 0.02) < -0.3
            || caveNoise.GetNoise(x * 0.02, y * 0.02, (z - 1) * 0.02) < -0.3;
    }

    // ─── Noise helpers ───────────────────────────────────────────────────

    private double getSurfaceRadius(Vector3d pos) {
        double nv = terrainNoise.GetNoise(pos.x() * NOISE_SCALE, pos.y() * NOISE_SCALE, pos.z() * NOISE_SCALE);
        return QuadSphere.planetRadius() + nv * TERRAIN_AMPLITUDE;
    }

    private boolean isArcticRegion(Vector3d pos) {
        double dist = pos.length();
        if (dist < 1) return false;
        double ny = pos.y() / dist;
        double pa = Math.acos(Math.abs(ny));
        double nv = biomeNoise.GetNoise(pos.x() * 0.1, pos.y() * 0.1, pos.z() * 0.1);
        return pa < 0.5 + nv * 0.15;
    }

    // ─── Vanilla API ─────────────────────────────────────────────────────

    @Override public int getMinY() { return 0; }
    @Override public int getSeaLevel() { return 0; }
    @Override public int getGenDepth() { return 16; }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor a, RandomState r) {
        BlockState[] air = new BlockState[getGenDepth()];
        for (int i = 0; i < air.length; i++) air[i] = Blocks.AIR.defaultBlockState();
        return new NoiseColumn(0, air);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types t, LevelHeightAccessor w, RandomState r) {
        double pr = QuadSphere.planetRadius();
        double ds = (double) x * x + (double) z * z;
        if (ds > pr * pr) return 0;
        double nv = terrainNoise.GetNoise(x * NOISE_SCALE, 0, z * NOISE_SCALE);
        return (int) Math.round(Math.sqrt(pr * pr - ds) + nv * TERRAIN_AMPLITUDE);
    }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public void addDebugScreenInfo(List<String> text, RandomState r, BlockPos pos) {
        double pr = QuadSphere.planetRadius();
        text.add("§6Blocky Planet");
        text.add(String.format("§7Planet: §f%s ⌀  §7(%s radius)",
            BlockyPlanetConfig.formatDiameter((int) (pr * 2)),
            BlockyPlanetConfig.formatRadius(pr)));

        Vector3d v = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        double dist = v.length();
        double sr = getSurfaceRadius(v);
        text.add(String.format("§7Surface: §f%.1f  §7Dist: §f%.1f  §7Depth: §f%.1f", sr, dist, sr - dist));
        text.add(dist < QuadSphere.getShellInnerRadius(0) ? "§7Layer: §6Core" :
                 dist < BlockyPlanetConfig.getNetherInnerRadius(pr) ? "§7Layer: §cLava" :
                 BlockyPlanetConfig.isInNetherRing(dist) ? "§7Layer: §4Nether" :
                 sr - dist <= SOIL_DEPTH ? "§7Layer: §aSurface" : "§7Layer: §8Crust");

        try {
            BlockAddress addr = BlockAddress.fromWorldPosition(v);
            text.add(String.format("§7Addr: %s  §7offset: %.2f", addr, v.subtract(addr.toWorldPositionImproved()).length()));
            Level w = BlockyPlanetMod.blockyWorld;
            if (w != null) text.add(String.format("§7Cubes in storage: §f%d", BlockyPlanetMod.getOrCreateStorage(w).size()));
        } catch (Exception e) { text.add("§7Addr: error"); }
    }
}
