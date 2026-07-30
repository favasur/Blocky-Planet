# How Blocky Planet Works — Full Architecture

> **Blocky Planet** is a Fabric 1.21.1 mod that replaces Minecraft's flat infinite world with a **spherical planet** — a fully-survivable, Earth-sized ball of blocks floating in void, with custom gravity always pulling toward its center.

---

## Part 1: Overall Mod Architecture

The mod has six subsystems that work together:

### ① Quad-Sphere Coordinate System

*Files: `QuadSphere.java`, `BlockAddress.java`, `SectorType.java`, `Int3.java`, `Vector3d.java`, `Vector2d.java`*

Vanilla Minecraft uses flat Cartesian coordinates `(x, y, z)` where `y` is up. A sphere uses angles. The bridge between them is the **quad sphere** — a cube wrapped around the sphere, with each of the 6 cube faces subdivided into a grid. Every 3D point in the world is projected onto this cube-sphere surface.

`BlockAddress` maps a world `(x, y, z)` to a sector/cell on the quad sphere, and its `toWorldPositionImproved()` gives the gravity-aligned position on the planet's surface.

`SectorType` defines the 6 faces of the cube (`RIGHT`, `LEFT`, `UP`, `DOWN`, `FRONT`, `BACK`). `Int3` packs cube coordinates. `Vector3d`/`Vector2d` do vector math.

### ② Planet Configuration & Config

*File: `BlockyPlanetConfig.java`*

A config file with a configurable planet **diameter** (default is Earth-like, configurable at runtime via a "Create New World" GUI). The planet has layered rings — a surface crust down to a core. The Nether exists as a hollow **ring** embedded inside the planet, between configurable inner and outer radii.

| Parameter | Range | Default (Earth) |
|---|---|---|
| Diameter | 500 – 129,000,000 blocks | 12,742,000 blocks |
| Radius | 250 – 64,500,000 blocks | 6,371,000 blocks |
| Nether depth ratio | 0.001884 × radius | ~12,000 blocks below surface |
| Nether half-thickness ratio | 0.00004 × radius | ~256 blocks (512 total) |

### ③ Custom Chunk Generator

*File: `BlockyPlanetChunkGenerator.java`*

This is the brain. When Minecraft needs a 16×16 column (a vanilla "chunk"), the generator:

1. Iterates every `(x, z)` in the 16×16 column.
2. For each column, it iterates `y` from `-yBound` to `+yBound` — the full vertical span of the planet.
3. For each `(x, y, z)`, it calls `getGravityAlignedBlock(pos, distFromCenter, planetRadius)`.
4. That method computes the **shell** this position belongs to (surface, crust, mantle, core, Nether ring) and returns the correct block: grass/stone at the surface, deepslate deeper, bedrock at the core, netherrack in the Nether ring, lava pools, etc.
5. Each block is written **twice**:
   - To the vanilla `Chunk` (for blocks within the 16-block world height — handles the surface section for rendering)
   - To `PlanetBlockStorage` (for ALL blocks at ANY `y` — our source of truth for infinite vertical range)

### ④ PlanetBlockStorage

*File: `PlanetBlockStorage.java`*

A sparse 3D block store that completely bypasses vanilla's height limits. It uses a `Long2ObjectOpenHashMap<BlockState[]>` keyed by `(cubeX, cubeY, cubeZ)` — each entry is a 4096-element `BlockState[]` representing a 16×16×16 cube.

- Cubes are created **on demand**; only populated cubes consume memory.
- Also stores a parallel `normals` map (`Long2ObjectOpenHashMap<Vector3d[]>`) that holds the surface normal (gravity-radial direction) for each block, enabling curved-block rendering.

### ⑤ Mixins — The Integration Layer

| Mixin | Target | What it does |
|---|---|---|
| `PlayerEntityMixin` | `PlayerEntity` | Applies custom gravity (`CustomGravity`) pulling toward planet center instead of straight down |
| `MixinLevel_CubicWorld` | `Level` / `World` | Redirects `getBlockState` and `setBlockState` to `PlanetBlockStorage`; overrides `isOutOfHeightLimit()` to always return `false` |
| `MixinWorldBorder_CubicWorld` | `WorldBorder` | Makes `contains()` always return `true` for Blocky Planet's border instance — no world border |

### ⑥ Custom Gravity

*Files: `PlayerEntityMixin.java`, `CustomGravity.java`*

Standard Minecraft gravity is a constant vector `(0, -1, 0)` — always down. Blocky Planet replaces it with a **radial** gravity vector that always points toward the planet's center. This means:

- Walking on the **top** of the sphere feels normal.
- Walking on the **bottom** feels like standing on the ceiling.
- **Jumping** always launches you perpendicular to the local surface.
- **Falling off** sends you curving toward the core.
- Holding **SPACE** while airborne activates a **thruster** that counteracts gravity.

The gravity direction at any point is computed from `BlockAddress` — it's the normalized vector from the planet center to your position.

---

## Part 2: Solid Block Structure of an Earth-Sized Planet

An Earth-sized planet has radius ≈ **6,371,000 blocks** (in Minecraft terms). That's 12.7 million blocks from one side to the other.

### Spherical Shell Structure (from outside in)

```
↑ Sky / Void
╔═══════════════════════════════════════╗
║    Surface Layer (r ≈ 6,371,000)      ║  ~4 blocks thick
║    Grass → Dirt → Stone               ║  The "crust" you see and walk on
╠═══════════════════════════════════════╣
║    Deep Rock Layer                     ║  ~32 blocks thick
║    Stone → Deepslate → Smooth Basalt   ║  Deeper rock with variety
╠═══════════════════════════════════════╣
║    Deepslate Mantle                   ║  ~64 blocks thick  
║    Deepslate + Ores                    ║  Mining gets harder
╠═══════════════════════════════════════╣
║    Nether Ring (hollow)               ║  A spherical shell of Nether
║    Netherrack, Lava, Soul Sand,        ║  Thickness = configurable
║    Glowstone, Gravel, Bedrock walls    ║  Has cave voids, lava pools
║    ~200-300 blocks thick               ║  Inner radius ≈ planetRadius × 0.7
╠═══════════════════════════════════════╣
║    Deepest Mantle                     ║  ~128 blocks thick  
║    Deepslate → Bedrock                 ║
╠═══════════════════════════════════════╣
║    Core Bedrock Shell                 ║  ~8 blocks thick
║    Solid Bedrock                       ║  Impassable, planet's structural core
╠═══════════════════════════════════════╣
║    Void Interior                       ║  Empty — the planet is hollow inside
║    (radius ≈ 0 to core radius)         ║  Potentially accessible via deep mining
╚═══════════════════════════════════════╝
↓ Toward center (r=0)
```

### How the Spherical Volume is Stored

This is the key insight for Earth-scale: **we don't store the full sphere as one monolithic block array**. That would require ≈ 10²¹ blocks — impossible. Instead:

1. **The volume is partitioned into 16×16×16 cubes.** Each cube is indexed by `(cubeX / 16, cubeY / 16, cubeZ / 16)` — just like the `PlanetBlockStorage` map keys.

2. **Only populated cubes exist in memory.** The `Long2ObjectOpenHashMap` only contains cubes that have been generated. Cubes in the void (far from the planet surface or inside the hollow core) don't exist at all — they return `null` when looked up, and the game sees them as air.

3. **The generator only generates cubes near the planet surfaces.** The `populateNoise` method in `BlockyPlanetChunkGenerator` iterates y from `-planetRadius` to `+planetRadius`, but in practice generates content only where shells exist. Each shell is hundreds of blocks thick, not millions — so each 16×16×16 cube either falls entirely inside a shell (gets filled), entirely in void (skipped), or spans a shell boundary (partially filled).

4. **Memory footprint.** An Earth-sized planet's surface area is ≈ 4π × (6.37×10⁶)² ≈ 5.1×10¹⁴ blocks². That's an enormous number of surface blocks. But in practice, only the loaded area around each player is generated. With a render distance of 10, the game loads ~33×33 chunks ≈ 1,089 surface columns = **~1 million surface blocks per player**. Each 16×16×16 cube handles 4,096 blocks, so the active surface fits in ~250 cubes = ~1 MB for blocks + ~0.5 MB for normals. **The system is memory-scalable because cubes are created lazily** — only the volume near players is materialized.

5. **The vanilla renderer sees only the top 16-block section** of each chunk (the dimension has `min_y=0, height=16`). Everything else is stored in `PlanetBlockStorage`. When a player walks around, the `Level.getBlockState` mixin makes them *feel* the full sphere — they stand on blocks at any Y, gravity pulls toward the center, and you can mine down through shell after shell — but only the surface layer renders visually. Adding full vertical rendering (all cubes at any Y) is the next frontier.

### What Makes Cosmic-Scale Travel Possible

- **No height limit** — `isOutOfHeightLimit()` always returns `false`; you can exist at any Y coordinate.
- **No world border** — `WorldBorder.contains()` always returns `true`; you can move to any `(x, z)`, no invisible wall.
- **Sparse storage** — Only populated cubes cost memory. The void is free.
- **Custom gravity** — Pulls toward the planet center, so you stay on the sphere regardless of where you are in orbit.
- **Curved-block normals** — Your local chunk is always a flat 16×16×16 cube of the sphere's volume, but with a surface normal pointing radially outward, ready for curved-block rendering that would tilt each block to match the sphere's curvature.

---

> **Bottom line:** The mod replaces a flat infinite world with a finite spherical volume, stores it in sparse 16×16×16 cubes, eliminates all vanilla limits on position and height, applies radial gravity, and embeds a Nether ring as a spherical shell deep inside the planet.
