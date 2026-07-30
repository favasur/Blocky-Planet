# Blocky Planet — Design Document

> A spherical overworld planet for Minecraft Fabric 1.21.1.
> No flat infinite world — you live on a ball.

---

## Table of Contents

1. [Mod Architecture Overview](#1-mod-architecture-overview)
2. [The Quad Sphere System](#2-the-quad-sphere-system)
3. [Sector Size at Earth Scale](#3-sector-size-at-earth-scale)
4. [The Shell Resolution Problem](#4-the-shell-resolution-problem)
5. [World Loading Performance](#5-world-loading-performance)
6. [PlanetBlockStorage — Sparse 3D Block Store](#6-planetblockstorage--sparse-3d-block-store)
7. [Memory Footprint Estimates](#7-memory-footprint-estimates)
8. [Curved-Block Surface Normals](#8-curved-block-surface-normals)
9. [Current Limitations & Needed Fixes](#9-current-limitations--needed-fixes)

---

## 1. Mod Architecture Overview

Blocky Planet replaces Minecraft's flat infinite world generation with a **spherical planet** — a survivable, Earth-sized ball of blocks floating in void, with custom gravity always pulling toward its centre.

The mod has six interconnected subsystems:

### ① Quad-Sphere Coordinate System
*Files: `QuadSphere.java`, `BlockAddress.java`, `SectorType.java`, `Int3.java`, `Vector3d.java`, `Vector2d.java`*

Vanilla Minecraft uses flat Cartesian coordinates `(x, y, z)` where `y` is "up". A sphere uses angles. The bridge between them is a **quad sphere** — a cube wrapped around the sphere, with each of its 6 faces subdivided into a grid. Every 3D point in the world is projected onto this cube-sphere surface.

`BlockAddress` maps a world `(x, y, z)` to a sector/cell on the quad sphere with four levels of hierarchy:

```
BlockAddress {
    int sectorIndex;   // 0–5  (six cube faces: RIGHT, LEFT, UP, DOWN, FRONT, BACK)
    int shellIndex;    // 0–N  (concentric 16-block-thick shells)
    Int3 chunkIndex;   // position within the shell's chunk grid
    Int3 blockIndex;   // position within the 16×16×16 chunk
}
```

This is analogous to a postal address: **Country → State → City → Building**.

### ② Planet Configuration
*File: `BlockyPlanetConfig.java`*

A config file with a configurable planet **diameter** (set via the "Create New World" GUI on a log slider). The planet has layered rings — a surface crust down to a core, with the Nether existing as a **hollow spherical shell** embedded inside the planet.

| Parameter | Range | Default (Earth) |
|---|---|---|
| Diameter | 500 – 129,000,000 blocks | 12,742,000 blocks |
| Radius | 250 – 64,500,000 blocks | 6,371,000 blocks |
| Nether depth ratio | 0.001884 × radius | ~12,000 blocks below surface |
| Nether half-thickness ratio | 0.00004 × radius | ~256 blocks (512 total) |

### ③ Custom Chunk Generator
*File: `BlockyPlanetChunkGenerator.java`*

The brain of the mod. When Minecraft needs a 16×16 column (a "chunk"), the generator:

1. Iterates every `(x, z)` in the 16×16 column.
2. For each column, iterates `y` from `-yBound` to `+yBound` — the full vertical span of the planet.
3. For each `(x, y, z)`, calls `getGravityAlignedBlock(pos, distFromCenter, planetRadius)`.
4. That method determines which **shell** this position belongs to (surface crust, deep rock, Nether ring, core, void) and returns the correct block type.
5. Every block is written **twice**: to the vanilla `Chunk` (for rendering within the 16-block world height) AND to `PlanetBlockStorage` (for ALL blocks at ANY `y` — our unbounded source of truth).

### ④ PlanetBlockStorage
*File: `PlanetBlockStorage.java`*

A sparse 3D block store (`Long2ObjectOpenHashMap<BlockState[]>`) that completely bypasses vanilla's height limits. Cubes are 16×16×16. Only populated cubes consume memory. Also stores surface normals (`Vector3d[]` per cube) for curved-block rendering.

### ⑤ Mixins — The Integration Layer

| Mixin | Target | What it does |
|---|---|---|
| `PlayerEntityMixin` | `PlayerEntity` | Applies radial gravity (`CustomGravity`) pulling toward planet centre |
| `MixinLevel_CubicWorld` | `World` | Redirects `getBlockState`/`setBlockState` to `PlanetBlockStorage`; overrides `isOutOfHeightLimit()` → always `false` |
| `MixinWorldBorder_CubicWorld` | `WorldBorder` | Makes `contains()` always return `true` for Blocky Planet dimension's border (by tracking which `WorldBorder` instances belong to Blocky Planet in a static set) |

### ⑥ Custom Gravity
*Files: `PlayerEntityMixin.java`, `CustomGravity.java`*

Standard Minecraft gravity is a constant vector `(0, -1, 0)`. Blocky Planet replaces it with **radial** gravity always pointing toward the planet centre.

- Walking on the "top" of the sphere feels normal.
- Walking on the "bottom" feels like standing on the ceiling.
- Jumping launches you perpendicular to the local surface.
- Falling off sends you curving toward the core.
- Holding SPACE while airborne activates a **thruster** that counteracts gravity (like a jetpack but simpler).

The gravity direction is the normalized vector from the planet centre to the player's position.

---

## 2. The Quad Sphere System

### How It Works

A quad sphere is constructed by taking a cube, subdividing each face into a grid, and normalizing (inflating) each vertex onto the unit sphere. This avoids the severe distortion of latitude/longitude mapping (no poles or singularities).

**The cube-to-sphere mapping:**
```
Cube face (u, v in [-1, 1]) → 3D cube vertex → normalise onto unit sphere → scale by radius
```

The mapping uses an "improved" transform (`QuadSphere.improvedMapping`) that pre-distorts vertices on the cube face to counteract the distortion introduced by spherical normalisation. This gives near-uniform block sizing across each sector face.

### Shell Architecture

The planet's volume is divided into **concentric shells**, each 16 blocks thick (radially). Each successive shell **doubles** its linear resolution:

| Shell | Inner Radius | Outer Radius | Blocks Per Sector Side | Total Blocks Per Sector Layer |
|---|---|---|---|---|
| 0 | 0 | 16 | 16 | 256 |
| 1 | 16 | 32 | 32 | 1,024 |
| 2 | 32 | 48 | 64 | 4,096 |
| 3 | 48 | 64 | 128 | 16,384 |
| 4 | 64 | 80 | 256 | 65,536 |
| 5 | 80 | 96 | 512 | 262,144 |
| 6 | 96 | 112 | 1,024 | 1,048,576 |
| 7 | 112 | 128 | 2,048 | 4,194,304 |
| … | … | … | … | … |
| N | N×16 | (N+1)×16 | 16 × 2ᴺ | (16 × 2ᴺ)² |

**Purpose:** This doubling ensures blocks stay roughly **square-shaped** at every depth. Without it, blocks near the centre would be squeezed thin (like pizza slices converging at the core). Each shell has its own "pixel grid" — a block at the surface of shell N maps to 4 blocks (2×2) at the bottom of shell N+1.

### From World Coordinates to BlockAddress

Given any world position `(x, y, z)`, the system:

1. Computes the distance from planet centre → determines shell index (distance ÷ 16)
2. Finds the sector (cube face) whose axis has the greatest absolute coordinate value
3. Projects the position onto that cube face to get `(u, v)` in [-1, 1]
4. Converts `(u, v)` and the shell's layer depth to a block index within the shell's grid
5. Splits that into a 16×16×16 chunk index and a local block index

---

## 3. Sector Size at Earth Scale

**Earth parameters:**
- Default diameter: 12,742,000 blocks (= 12,742 km)
- Default radius: R = 6,371,000 blocks (= 6,371 km)

### Sphere Surface Area

```
Surface area = 4πR² = 4π × (6,371,000)² = 5.10 × 10¹⁴ blocks²
```

That's **510 trillion square blocks** — the entire surface of the planet.

### Sector Breakdown

The quad sphere divides this into 6 sectors:

```
Area per sector = 4πR² / 6 = 8.50 × 10¹³ blocks² per sector
```

Each sector face, when unwrapped onto its cube face, is roughly a square:

```
Sector side length ≈ √(8.50 × 10¹³) ≈ 9,220,000 blocks ≈ 9,220 km
```

For comparison:
- 1 sector ≈ the area of **Russia** (17 million km² vs 8.5 million km² per sector — roughly half the area of Russia)
- Each sector's side ≈ the distance from **London to Tokyo** (~9,600 km)

### Arc Length Per Sector

Since each cube face covers ¼ of a great circle arc:

```
Arc length per sector side = πR/2 = π × 6,371,000 / 2 ≈ 10,007,000 blocks ≈ 10,000 km
```

So a sector face at Earth scale is approximately **10,000 km × 10,000 km**.

### Block Density at 1:1 Scale

To have a 1:1 mapping where each stored surface block = one Minecraft block on the sphere surface:

```
Blocks per sector layer = 10,007,000 × 10,007,000 ≈ 1.0 × 10¹⁴ blocks per sector
Blocks for all 6 sectors = 6.0 × 10¹⁴ blocks
```

That's **600 trillion blocks** for just the outermost layer of blocks on the planet surface.

Each 16×16×16 cube holds 4,096 blocks. So the outermost layer alone requires:

```
(6.0 × 10¹⁴) / 4,096 ≈ 1.46 × 10¹¹ cubes ≈ 146 billion cubes
```

This is far too many cubes to store in memory all at once — it would require **multiple terabytes of RAM** for just one shell layer.

### Why This Is (Mostly) OK

**Minecraft doesn't load the whole world at once.** Just as vanilla Minecraft only loads chunks within a player's render distance (typically 33×33 = 1,089 chunks), Blocky Planet only generates and loads **cubes near players** on the sphere's surface. The rest of the planet exists only as potential — cubes are created lazily on demand from `PlanetBlockStorage`.

The `PlanetBlockStorage` key uses 21 bits per axis (x, y, z), supporting a cube coordinate range of [-1,048,575, +1,048,575] per axis, which maps to a block space range of [-16,777,215, +16,777,215] per axis. Earth's radius (6,371,000) fits comfortably within this range. The key packing is:

```java
key = (cx & 0x1FFFFFL) | ((cy & 0x1FFFFFL) << 21) | ((cz & 0x1FFFFFL) << 42)
```

---

## 4. The Shell Resolution Problem

### The Exponential Overflow

The current shell system uses:

```java
blocksPerSide(shellIndex) = BASE_RESOLUTION × (1 << shellIndex)  // 16 × 2^shellIndex
```

For Earth's radius (6,371,000 blocks), the surface shell is at:

```
shellIndex = floor(6,371,000 / 16) = 398,187
```

At shell 398,187:

```
blocksPerSide = 16 × 2^398,187
```

This number is astronomically large — far more than the number of atoms in the observable universe. Java's `int` overflows at shell 28 (where `16 × 2^28 = 4,294,967,296 > Integer.MAX_VALUE`).

### What This Means

The exponential shell-doubling system **cannot scale to Earth-sized planets** in its current form. It works correctly only for planets up to ~450 blocks radius (shell 28, where int overflow begins, giving a practical limit of shell ~27 = radius ~432 blocks).

At Earth scale:
- `getBlocksPerSide()` overflows and produces garbage values
- `BlockAddress.fromWorldPosition()` uses the result of `getBlocksPerSide()` to compute `blockU` and `blockV` coordinates
- These overflow, producing incorrect or negative indices
- `clamp()` caps the result, but to a nonsensical value

### The Required Fix

A fixed maximum resolution (e.g., 4,096 or 8,192 blocks per sector side) for all shells beyond a certain depth, with some creative math to keep blocks reasonably square at the surface. This means:

1. **Cap the resolution** at `MAX_RESOLUTION` (e.g., 4,096 blocks per side) — this gives ~16.8 million blocks per sector layer, or ~100 million blocks total across all 6 sectors per shell layer.

2. **Accept inner-shell distortion** — blocks near the planet centre will be slightly trapezoidal (wider at the outer end), but players rarely visit these depths. Normal gameplay in the surface shell (where resolution is highest) will look correct.

3. **Scale differently**: Instead of `16 × 2^shellIndex`, use a formula that grows linearly or logarithmically to a fixed maximum at the surface radius.

**Proposed formula:**
```java
int getBlocksPerSide(int shellIndex, int maxShellIndex) {
    int maxResolution = 4096; // configurable
    // Linear interpolation from BASE_RESOLUTION at shell 0 to maxResolution at maxShellIndex
    long result = BASE_RESOLUTION + (long)(maxResolution - BASE_RESOLUTION) * shellIndex / maxShellIndex;
    return (int) Math.min(result, maxResolution);
}
```

This gives 4,096 blocks per side at the surface — enough for 16.8 million blocks per sector face, or ~100 million blocks per 16-block-thick shell layer. Each block would span approximately 2,500 blocks (~2.5 km) on an Earth-sized surface — not 1:1 mapping, but enough for large-scale terrain features.

For true 1:1 block mapping at Earth scale, a **different strategy** is needed (see §9).

---

## 5. World Loading Performance

### The Generation Loop Problem

The current `populateNoise` method in `BlockyPlanetChunkGenerator` iterates:

```java
for each (x, z) in 16×16 chunk:
    compute yBound = sqrt(planetRadius² - x² - z²)
    for y from -yBound to +yBound:
        compute block state at (x, y, z)
```

**For a chunk near the surface at Earth scale:**

```
planetRadius = 6,371,000
yBound ≈ 6,371,000 (for a chunk near the equator at x≈0, z≈0)
```

That's **12,742,001 y-values × 256 (x,z) pairs = 3.26 billion iterations per chunk**.

Each iteration calls:
- `Math.sqrt()` for distance-from-centre
- `BlockAddress.fromWorldPosition()` which calls `length()`, `QuadSphere.getShellIndex()`, `QuadSphere.getLayerInShell()`, `QuadSphere.projectToCubeFace()`, and multiple index calculations
- `getGravityAlignedBlock()` which calls `getSurfaceRadius()`, noise lookups, conditional logic
- If a block is placed: `storage.setBlockState()` and `storage.setNormal()` plus the BlockAddress normal computation

At a generous estimate of 100 ns per iteration (far too optimistic for Java with all these method calls), one chunk would take **326 seconds (~5.5 minutes)** to generate. With a render distance of 10 loading 1,089 chunks, the game would hang for **over 100 hours** on world load.

### The Fix: Generation Band

Instead of iterating all y-values, generate only within **bands** around each significant shell:

| Band | Y Range Around Surface | Thickness | Reason |
|---|---|---|---|
| Surface crust | `-yBound` to `-yBound + 256` | 256 blocks | Grass, dirt, stone, deepslate |
| Nether ring | `R×0.7` ± 128 | 256 blocks | Nether biome shell |
| Core bedrock | `0` to `16` | 16 blocks | Planet's structural core |

Total: **~528 y-values per column** instead of 12.7 million — a **24,000× reduction**.

The generator skeleton becomes:

```java
for each (x, z) in chunk:
    for each generation band:
        for y from band.startY to band.endY:
            compute and place block
```

This reduces chunk generation time from minutes to milliseconds for Earth-scale planets.

### Lazy Cube Generation

A further optimisation: don't generate all columns in a chunk during `populateNoise`. Instead, the `WorldChunk` mixin can intercept `getSection()` calls (which happen when the renderer needs geometry for a given Y level) and generate cubes on demand. This is the standard approach used by Cubic Chunks — generation is driven by render distance, not by column boundaries.

---

## 6. PlanetBlockStorage — Sparse 3D Block Store

### Data Structure

```
Long2ObjectOpenHashMap<BlockState[]> cubes       // Block data
Long2ObjectOpenHashMap<Vector3d[]>   normals     // Surface normals
```

- **Key:** 63-bit packed `(cubeX, cubeY, cubeZ)`, 21 bits per axis → supports [-1,048,575, +1,048,575] cube coordinates = [-16,777,215, +16,777,215] block coordinates
- **Value:** `BlockState[4096]` — a 16×16×16 cube. `Arrays.fill()` with air on creation.
- **Normal:** Parallel `Vector3d[4096]` — surface normal per block, allocated separately

### Access Patterns

```java
// Read
BlockState state = storage.getBlockState(x, y, z);  // returns AIR if cube doesn't exist

// Write
storage.setBlockState(x, y, z, state);               // creates cube on demand

// Normal (for curved rendering)
Vector3d normal = storage.getNormal(x, y, z);        // returns null if not stored
storage.setNormal(x, y, z, normal);                  // creates normals array on demand
```

### Key Properties

- **Sparse:** Only cubes that have been generated exist in the map. The void returns air.
- **Lazy allocation:** Cubes and normals arrays are created on first write to any block in that cube.
- **No ChunkSection dependency:** Uses bare `BlockState[]` arrays, avoiding the `ChunkSection(BlockState, Registry<Biome>)` constructor that requires a biome registry reference (which changed between Yarn mappings).
- **No height limit:** Cubes can exist at any Y coordinate within the key range (±16.7 million blocks).

---

## 7. Memory Footprint Estimates

### Per Cube

| Component | Size |
|---|---|
| `BlockState[4096]` (64-bit compressed OOPs) | ~32 KB |
| `Vector3d[4096]` (3 doubles each) | ~96 KB |
| Long key in hashmap | ~24 bytes |
| HashMap entry overhead | ~32 bytes |
| **Total per loaded cube** | **~128 KB** |

### Per Player (render distance 10)

A player on the surface loads a roughly circular area of cubes. The exact count depends on surface curvature, but approximate:

- View distance: 10 chunks = 160 blocks radius
- Surface area loaded: π × 160² ≈ 80,425 blocks²
- Cubes needed at surface: ~80,425 / (16 × 16) ≈ 314 cubes
- **Total memory: 314 × 128 KB ≈ 40 MB**

For 10 players on the same server, each exploring different areas: **~400 MB** total.

### Earth's Full Surface (not loaded, for comparison)

If someone tried to generate the entire Earth-scale planet surface:

- Surface cubes: ~146 billion
- Total memory: 146 × 10⁹ × 128 KB = **~18 exabytes**

This is a fundamental constraint — the full planet can never be loaded into RAM. Only the area around each player is materialized.

---

## 8. Curved-Block Surface Normals

### What They Are

Each block on the sphere's surface has a **normal** — a unit vector pointing radially outward from the planet centre at that block's gravity-aligned position. For a sphere, no two surface blocks have exactly the same normal (the direction changes continuously across the surface).

Within a single vanilla chunk (16×16 blocks), the normals vary by:

```
Angular difference across a chunk at Earth scale:
  chunk_angle = 16 / R = 16 / 6,371,000 ≈ 2.5 × 10⁻⁶ radians ≈ 0.00014°
```

This is imperceptibly small — for an Earth-sized planet, a 16×16 chunk is effectively flat. The curvature across the chunk is less than 1/1000th of a degree.

### Why Store Them?

For **smaller planets** (e.g., the minimum 250-block radius), the curvature is significant:

```
Angular difference across a chunk at R=250:
  chunk_angle = 16 / 250 ≈ 0.064 radians ≈ 3.7°
```

Here, a 16×16 chunk curves noticeably. Surface normals allow a future renderer to:
1. Read the four corner normals of a block
2. Compute a quaternion/blend that tilts the block's rendered mesh
3. Make adjacent blocks meet at slightly different angles, forming a continuous curved surface

### Storage

Normals are stored per-block in `PlanetBlockStorage` as `Vector3d` (3 doubles = 24 bytes). They're allocated lazily alongside the block state array. Total memory overhead for a loaded area: ~96 KB per cube × ~314 cubes = ~30 MB per player (in addition to the ~10 MB for block states).

---

## 9. Current Limitations & Needed Fixes

### 1. 🔴 Shell Resolution Overflow at Earth Scale

**Problem:** `getBlocksPerSide()` uses `1 << shellIndex`, which overflows for shell indices > 28. At Earth radius (shell 398,187), all shell resolution calculations produce garbage.

**Fix:** Replace exponential resolution growth with a capped, linear or logarithmic formula. See §4 above.

**Priority:** CRITICAL. The mod literally does not work at Earth scale without this fix.

### 2. 🔴 Generation Loop Iterates All Y

**Problem:** `populateNoise` iterates all 12.7 million y-values per column at Earth scale, resulting in billions of iterations per chunk.

**Fix:** Generate only within bands (surface crust, Nether ring, core) — ~528 y-values per column instead of 12.7 million. See §5 above.

**Priority:** CRITICAL. The mod cannot finish generating a single chunk at Earth scale without this fix.

### 3. 🟡 No Vertical Rendering of PlanetBlockStorage Cubes

**Problem:** Only the vanilla chunk's single section (Y=0..16) renders visually. Blocks at extreme Y values (e.g., Y=6,371,000 at the surface) are stored in `PlanetBlockStorage` but don't appear on screen. The renderer iterates the chunk's `sectionArray` (1 element) and never looks at our cubes.

**Fix:** A `WorldChunk.getSection(int)` mixin that routes section lookups to our `PlanetBlockStorage` cubes, creating temporary `ChunkSection` objects for the renderer.

### 4. 🟡 `isOutOfHeightLimit` Mixin Uses `require=0`

**Problem:** The `isOutOfHeightLimit` injections use `require=0` because Mixin can't statically verify these default methods from `HeightLimitView` exist on `World`. At runtime, they may or may not apply depending on the JVM's method resolution.

**Fix:** Instead of mixing into `isOutOfHeightLimit`, mixin into the callers (e.g., `Entity.move()`, `World.isValid()`, `PlayerEntity.tick()`) to skip out-of-bounds checks for Blocky Planet dimension.

### 5. 🟡 Dual-Write Duplicates Work

**Problem:** Blocks within the vanilla world height (Y=0..15) are written to both the vanilla chunk AND PlanetBlockStorage. This is double work.

**Fix:** Either skip the vanilla chunk write (risks breaking vanilla rendering) or use the vanilla chunk as a cache and read-through to our storage. Further investigation needed.

### 6. 🟡 Thread Safety of `BlockyPlanetMod.blockyWorld`

**Problem:** The static `blockyWorld` field is set on the server thread during `SERVER_STARTED` but read from chunk generator threads during `populateNoise`. No happens-before relationship.

**Fix:** Use `volatile` on the field, or pass the `World` reference through the chunk generator's constructor rather than relying on a static field.

### 7. 🟢 Dead Code

`PlanetBlockStorage.setCubeNormals()` is defined but never called. Remove.

---

## Appendix: Key Numbers Summary

| Quantity | Earth Value | Notes |
|---|---|---|
| Planet radius | 6,371,000 blocks | 6,371 km |
| Planet diameter | 12,742,000 blocks | 12,742 km |
| Sphere surface area | 5.10 × 10¹⁴ blocks² | 510 trillion m² |
| Sector area (1 of 6) | 8.50 × 10¹³ blocks² | ~85 trillion m² |
| Sector side length | ~10,007 km | Arc length |
| Surface shell index | 398,187 | 6,371,000 / 16 |
| Cubes for full surface | ~1.46 × 10¹¹ | 146 billion 16×16×16 cubes |
| Cubes loaded per player | ~314 | 10-chunk render distance |
| Memory per loaded cube | ~128 KB | Block state array + normals |
| Memory per player | ~40 MB | 314 cubes loaded |
| Y iterations per chunk (current) | 3.26 billion | 12.7M y-values × 256 (x,z) |
| Y iterations per chunk (fixed) | ~135,168 | 528 y-values × 256 (x,z) |
| Speedup from fix | ~24,000× | Band-based generation |
| Curvature across 16-block chunk | 0.00014° | Imperceptible at Earth scale |
| Min radius curvature across chunk | 3.7° | Noticeable at R=250 |
| Max storage key range | ±16.8M blocks | 21-bit key per axis |
