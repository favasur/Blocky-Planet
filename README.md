# Blocky Planet — Minecraft Rust Edition (Horizon)

A spherical planet of **any size**: a 500-block moon, an Earth-sized ball, or a
world with a 9,000,000,000,000,000,000-block radius. You live on the outside of
a ball of blocks, gravity pulls towards its centre, and you can dig from the
surface through real crust, mantle and core layers.

This is the Rust Edition rewrite of the unfinished Java mod. Only its design
documents were used; the code is new.

---

## Why a native mod

|  | Content mod | Native mod |
| --- | --- | --- |
| File | `.hmod` | `.hnmod` (or a `.hmod` with native code) |
| Contains | blocks, items, recipes, models, textures | Rust compiled into the game |
| Can it change the world's shape, gravity or movement? | No | Yes |

Blocky Planet changes how the world is shaped, where "down" is, and how far a
player can go, so it is a **native source mod** (`horizon-mixin.toml` + a
`[[crate]]`). The small amount of content the project template shipped with
(`glowing_amethyst`) stays in `assets/` and `data/`; a `.hmod` may carry both
content and native code under the same mod id.

## The height limit, and why the planet does not live in a level

Horizon, like Minecraft, gives each level a fixed build range:

* `BlockPos` packs Y into **12 bits**;
* `min_y + height <= 2032` (`docs/world-height.md`);
* a chunk is a fixed column of sections, with `SectionMask` at 256 sections;
* light, saves, the network protocol and the mesher all assume that column.

That limit cannot be raised from a mod: it is a bit layout and a protocol
constant, not a config value. A naive "make the world taller" patch would have
to change block positions, chunk storage, lighting, saves and the wire format
all at once — and would still only reach 4064 blocks.

So the planet does **not** live in a level. It lives in **planet space**:

```
planet space (i64/f64, unbounded)          engine (f32, ≤2032 tall)
──────────────────────────────────         ────────────────────────
sector 0..5 (quad sphere)                  one small local box
shell 0..~19 (doubling)                    gravity-aligned: +Y is up
block address: sector/shell/chunk/block    chunk columns near the player
radius: any u64                            window slides (floating origin)
```

The engine is only ever handed a **local window** around the player, rotated so
local **+Y** points away from the planet centre. When the player walks far
enough, the origin is rebased — a floating origin, the same trick No Man's Sky
uses to draw planets with 32-bit floats. The planet's size never reaches the
engine as a coordinate, so it never hits the 2032 ceiling.

This is exactly the article's structure, with the one fix the Java version
needed: shells double their **layer count and resolution together**, which makes
the shell index logarithmic in the radius. Earth-sized (6,371,000) is shell
**18**, not shell 398,187, and no `int` ever overflows.

## The model

### Sectors — a quad sphere

A cube's six faces, each with a right-handed grid `(u, v)`, inflated onto the
sphere. Unlike a latitude/longitude map there is no pole and no singularity.

```rust
let (sector, u, v) = Sector::from_planet(p);   // u, v in [-1, 1]
let point = sector.position(u, v, radius);     // back to planet space
```

### Shells — cubic blocks at any depth

Shell `k` starts at `base_layers · (2^k − 1)` and is `base_layers · 2^k` layers
thick; its grid is `base_resolution · 2^k` blocks per face side.

| shell | inner radius | layers | blocks per side |
| --- | --- | --- | --- |
| 0 | 0 | 16 | 25 |
| 1 | 16 | 32 | 50 |
| 2 | 48 | 64 | 100 |
| 3 | 112 | 128 | 200 |
| … | … | … | … |
| 18 | 4,194,288 | 4,194,304 | 6,553,600 |

At the bottom of a shell, block width ≈ `(π/2 · r_inner) / resolution` ≈ 1
layer, so blocks stay roughly cubic. The 2× ratio is what lets chunk edges line
up; the price is that blocks at the top of a shell are up to twice as wide as at
the bottom, which the article accepts.

### Blocks at depth — the shape contract

A block is not a cube: it is a spherical **frustum**. Its bottom face sits at
`r_inner` and its top at `r_inner + 1`, so its width grows with radius and its
side faces are trapezoids. Storing the eight corners (not a cube size) is what
makes rendering and collision exact. Four invariants keep that math from
reaching the game as a crash or a NaN:

1. **Bounded aspect ratio.** Shells double thickness and resolution together,
   so `r_outer / r_inner` is at most 3 (shell 1) and about 2.3 from shell 2
   outward. A shape whose longest edge over shortest edge exceeds `3.5` is
   rejected.
2. **Minimum edge.** A shape narrower than `0.1` block anywhere is rejected —
   no zero-width wedge is ever handed to the mesher or to collision.
3. **Cartesian core tier.** Below `r = 16` the shell grid is not used at all.
   Those cells meet at radius zero and collapse; inside instead is a plain
   `1x1x1` cube grid in planet space. No division by a zero radius.
4. **Solid core, Cartesian tier.** Inside `r = 16` the shell grid is replaced
   by 1×1×1 cubes, so the planet can be solid to its centre without ever
   emitting a wedge that meets at zero radius. From `r = 16` outward the chord
   width is at least about one block (1.48 at r=64, 1.45 at r=500, 2.26 at
   Earth size), so a deep block is never paper-thin.

`Planet::geometry_at` is the single gate: it returns a validated frustum, a
core cube, or `None`. Nothing unvalidated leaves the model. The article's two
topology breaks are modelled too: at a shell boundary one block has **four**
neighbours above (and four map back to one below) — `BlockAddress::above` /
`below` — and a structure must choose a shell before placement, or its roof
shrinks by that 2x2 factor. Sector corners are resolved by a fixed dominant-axis
tie-break, so a corner block belongs to exactly one sector rather than being
written by three.

Beyond `2^53` blocks, `f64` can no longer resolve a single block. The model
does not pretend otherwise: shape validation fails and `geometry_at` returns
`None` instead of emitting NaN geometry.

### Block address

`sector → shell → chunk (16³) → block`, e.g. Earth's `+X` surface at shell 18.
Addressing is `O(1)`; storage is sparse, so a 6.5-million-block-wide face costs
nothing until a block is actually written.

### Terrain

Sampled from seeded 3D value-noise FBM **on the unit sphere**, not from a 2D
height map — no seam and no latitude distortion.

The planet is solid, and its default layers are Earth's real ones, stored as
fractions of the surface radius so they scale with the diameter:

| layer | thickness at Earth | share of volume |
| --- | --- | --- |
| crust | 20 km | ~0.9% |
| mantle | 2,871 km | ~83% |
| outer core | 2,260 km | ~15.6% |
| inner core | 1,220 km | ~0.7% |

`LayerProfile::layer_at` classifies a radius in O(1). The top four blocks of
the crust are grass and dirt; below that the layer's block takes over (stone,
deepslate, lava, bedrock). A world-creation editor can change every thickness;
the order and slider ranges are in [`docs/WORLD-CREATION.md`](docs/WORLD-CREATION.md).

A solid planet cannot be generated column by column. Generation is a **bounded
band**: 256 blocks around the surface, or around the player when underground.
That is 0.004% of Earth's radius, and `LocalWindow::checked` refuses any window
above 4,194,304 blocks, so the Java version's full-radius 3.26-billion-block
chunk cannot be expressed. `Column` samples the surface noise once per column;
`planet-lab` measures the per-column speedup on a 16×16×64 chunk.

### Gravity

```rust
g(r) = g_surface                    if r >= R
     = g_surface · (r / R)          if r < R
```

Constant above the surface (predictable jumping), falling to zero at the
centre, where the planet pulls equally in every direction — the article's
"vibes-based" model. The direction is always `-normalize(position)`.

### The local frame

`LocalFrame::at(feet)` builds `(east, up, north)` from the radial up vector.
`to_local` / `to_planet` convert between planet space and the engine's box.
`GenerationWindow` builds the bounded block band around a moving player;
`ChunkStream` tracks the spherical 16³ chunks and returns only the chunks that
enter the window after a horizontal or vertical move. `EngineWriter` maps those
chunks to engine-local columns, emits a `WriteBatch`, and rebases the frame
when the player moves more than 256 blocks — clearing only the blocks it
generated. The native `player_tick` injection converts each non-empty batch to
one `NativeBlockEdit` and calls `native_edit_blocks` (API 126).

## Layout

```
blocky-planet/
├── Cargo.toml                 workspace: core + lab
├── horizon-mod.toml           content manifest (existing template)
├── assets/ data/              existing content (glowing_amethyst)
├── native/                    the .hnmod bundle
│   ├── horizon-mixin.toml     [mod] + [[crate]] planet + HUD/command injections
│   ├── runtime.rs             constants, selected planet, HUD text
│   ├── hud.rs                 bottom-left diagnostics box
│   ├── command.rs             /planet, /planet radius, /planet seed, /planet probe
│   └── planet/                the pure model (compiled into the game)
│       └── src/{sector,shell,address,noise,planet,layers,frame,stream}.rs
├── tools/planet-lab/          CLI: stats, ASCII cross-section, timing
└── docs/MATH.md               formulas and worked numbers
```

## Build and test

The model has no dependencies, so it builds and tests anywhere:

```bash
cargo test                 # 35 model tests
cargo run -p planet-lab    # Earth-sized planet, cross-section, timing
cargo run -p planet-lab -- 500 7
cargo run -p planet-lab -- 12742000 42
```

The native mod is built by the launcher, which compiles it into the game:

```bash
horizon-launcher native-build native --instance "Native mods" --trust-native-code
```

The first build downloads Rust and Horizon's source and takes 20–30 minutes;
later builds compile only what changed. `native-build` expects a bundle folder
(a folder with `horizon-mixin.toml`), which is `native/` here.

In game (**Options → Mods → Blocky Planet** must be on), with the engine box
anchored to the planet's `+X` surface until the streaming rebase lands:

* the bottom-left box shows the planet radius, your sector/shell/address, and
  the block you are standing in;
* `/planet` prints the planet and your address;
* `/planet radius 500` / `/planet radius 12742000` changes the planet size;
* `/planet seed 42` changes the terrain;
* `/planet probe` counts solid blocks in the 17³ local window around you.

## Status

**Milestone 1 — the unbounded model (this commit).**

* [x] 64-bit planet space, any radius up to 9.2e18, tested at 500 … 9e18
* [x] quad-sphere sectors with a right-handed basis and seam flip
* [x] doubling shells that do not overflow (the Java version's fatal bug)
* [x] block addressing and chunk decomposition
* [x] real Earth/Mars/Moon layer profiles (Earth: ~0.9% crust, ~83% mantle,
      ~15.6% outer core, ~0.7% inner core), customizable per world
* [x] bounded generation band for a solid planet; full-radius windows refused
* [x] `EngineWriter` + native `player_tick` adapter using `native_edit_blocks`
      (written against the API; not yet compiled against a Horizon commit)
* [x] validated frustum shapes, Cartesian core tier, 1↔4 shell neighbours
* [x] local frame + floating origin + window sampling, tested at Earth scale
* [x] native bundle, HUD and commands written against Horizon's real hook
      signatures (copied from `coords-hud`); not yet compiled against a
      Horizon commit, because that build is a 20–30 minute game compile

**Next, and what it needs**

1. **Streaming writer** — fill the local window from `LocalWindow` into real
   chunks when the frame rebases, and write block edits back to the planet
   store. Uses the ordinary server block helpers; no engine patch needed.
2. **Radial gravity and camera up** — Horizon's hook catalog has no gravity
   hook today, so this needs a raw-target injection into
   `horizon_client_lib::motion` (pinned to a Horizon commit), or a new upstream
   hook. Until then the engine still pulls straight down, and the planet is a
   model you can inspect, not yet a world you can walk around.
3. **Curved rendering** — Horizon's `world_warp` hook (API 415) takes WGSL that
   every terrain vertex passes through, which is the right place to bend a flat
   local patch onto the sphere.

I will not claim the planet is walkable until step 2 is done: that is the one
piece the current API cannot express, and it is called out here rather than
hidden behind a screenshot.

## Earth-sized planets

What an Earth-sized, playable planet needs — bent horizon, tapered blocks,
spherical chunks, vertical LOD, radial gravity and atmosphere — is assessed
feature by feature in [`docs/PLAN-EARTH.md`](docs/PLAN-EARTH.md). The short
version: the model supports the size; curvature is invisible locally (0.08
blocks over 64 chunks), so the horizon, far LOD and atmosphere are rendering
and hook problems, not planet-data problems.
