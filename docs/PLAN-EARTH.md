# An Earth-sized, playable Blocky Planet — capability and plan

Radius 6,371,000 blocks (1 block = 1 m), diameter 12,742 km. This page is the
honest answer to "what would it take", separated into what the model already
does, what Horizon's mod API can do, and what needs engine work.

## Verdict

| Feature | Capacity today | What it needs |
| --- | --- | --- |
| Planet data of Earth size | **Yes, done** | Shell model: 19 shells, 6.5M blocks per face side, sparse storage |
| Properly bent horizon | **Yes, visual** | `world_warp` vertex WGSL over terrain, far tiles and entities |
| Radially bent (tapered) blocks | **No, in engine** | Model has exact frustums; engine mesher only draws cubes |
| Spherical chunks | **Mod-side only** | `ShellChunk` boxes; engine chunks are fixed `16 x N x 16` columns |
| Vertical dynamic LOD | **Planned here** | Model emits `LodPlan`; feeding far terrain needs a hook that is not released |
| Earth-sized local play | **Mostly** | Streaming writer + local patch; no engine height change |
| Radial gravity | **Partial** | `entity_set_velocity` helper + tick hook; camera roll and client prediction unresolved |
| Atmosphere and space | **Shader pack** | `.hshader` sky/fog stages; no native sky API for a planet shell |
| Walking all the way around | **No** | Needs motion + camera-up hooks (or raw injections) |

## The numbers that set the design

Horizon distance `sqrt(2Rh + h²)`:

| eye altitude | horizon |
| --- | --- |
| 2 m | 5.0 km |
| 100 m | 35.7 km |
| 1 km | 113 km |
| 10 km | 357 km |
| 100 km | 1,133 km |

Surface drop over a distance, `R - sqrt(R² - d²)`:

| distance | drop |
| --- | --- |
| 1,024 blocks (64 chunks) | 0.08 blocks |
| 1 km | 0.08 blocks |
| 10 km | 7.8 blocks |
| 100 km | 785 blocks |

That is the whole rendering argument: **within any normal render distance the
planet is flat to under a tenth of a block**. Curvature is not a local problem;
it is a horizon problem. The engine can keep drawing a local flat patch, and a
warp shader only has to bend the distant view.

## What "bent blocks" really means

A shell block is a frustum whose width grows with radius. The model computes
its eight exact corners (`BlockShape`). Horizon's chunk mesher emits cubes, and
there is no hook that replaces a block's mesh. So:

* **Patch-level bending** — project every vertex of the local patch onto the
  sphere — is possible in `world_warp` today. It moves terrain, entities and far
  tiles, but not the hand, sky, particles or block outlines.
* **Per-block taper** — the real frustum — needs a custom renderer. It could
  be built on `native_world_primitives` / `native_render_needs`, but that means
  writing the terrain pipeline, not just a shader. Until then, blocks are
  visually cubes; the frustum is the collision/query truth.

This distinction matters: you can ship a round planet with cube blocks long
before you can ship tapered blocks.

## Streaming: what the player actually holds

The engine holds one local box. A 512 × 512 footprint with a 256-block surface
band is:

```
512/16 = 32 chunks per side
32 x 32 x (256/16) = 1,024 chunks
1,024 x 4,096 = 4.2M block positions
```

At two bytes per block state that is ~8 MB, before light and meshes — fine.
Full columns to the 2032-block ceiling would be 32×32×127 ≈ 130k chunks and is
not needed: only the band near the surface is materialized, with the planet
model answering everything deeper on demand.

**Why this does not freeze.** The planet is solid — real crust, mantle and
core — but a full column is never generated. `LayerProfile::layer_at`
classifies a radius in O(1), and generation is a 256-block band around the
surface (or around the player when underground). `Column` samples the surface
noise once per column; a 16×16×64 chunk measures 0.5 ms against 18.8 ms for
per-block noise in a debug build. A caller that asks for a full-radius window
is refused by `LocalWindow::checked` (4,194,304 blocks), so the Java version's
3.26-billion-iteration chunk cannot be expressed. The 6.37 million solid blocks
in a column are classified only when queried, never walked.

The hard part is the **rebase**. When the window moves, blocks must be re-keyed
from planet space into the new local frame and remeshed. Rebasing on a coarse
lattice (e.g. every 256 blocks) reuses most chunk content; rebasing every step
does not. For Earth size, the local up vector changes by 1° per 111 km, so the
frame does not need re-orienting during ordinary play at all; it only needs
shifting.

## Gravity and the camera

Horizon's released hooks have **no gravity hook**. It does have
`entity_set_velocity` (a helper) and `entity_tick_post` (server), so a mod can
overwrite an entity's velocity every tick: cancel vanilla's downward pull and
add `-normalize(pos) * g`. That is enough to bend trajectories on the server.
It is not enough for a seamless first-person experience:

* player movement is predicted client-side; server velocity changes rubber-band;
* the camera has yaw and pitch only (`camera_pose`), no roll, so local up is
  always +Y;
* mob AI and pathfinding assume +Y is up.

The pragmatic design for Earth size is therefore: **anchor the local frame at
the player's surface point, keep local +Y as up, and use vanilla movement and
gravity.** Over a play session the radial direction drifts by a fraction of a
degree, which is imperceptible and can be absorbed by a rare, deliberate
rebase. Walking to the other side of the planet is then a sequence of frame
rebases, not continuous rotation. True continuous circumnavigation needs a
motion/camera-up hook (or a pinned raw injection) that does not exist yet.

## Spherical chunks and LOD

Engine chunks are columns: fixed `x, z`, a stack of sections, 12-bit Y. They
cannot be spherical. The spherical unit lives in the model:

* `ShellChunk` is a `16 x 16 x 16` box in shell space (`sector, shell, i,
  layer, j`), with exact frustum geometry per block.
* A local patch is a projection of nearby shell chunks into engine chunk
  columns; the engine never learns that the source was spherical.
* `LodPlan` (in `lod.rs`) turns altitude into distance rings and quad-sphere
  levels: near rings at level ~24, the horizon at level ~20 for a 2 m eye, and
  coarser rings as the eye rises. `tile_arc` is chosen so a tile is about one
  pixel at its distance.

Feeding those rings to the engine's far terrain requires the `native_lod_column`
hook. It is documented (`docs/native-mods.md`, "world generation") but marked
with a draft API placeholder and is **not in the released hook catalog** (434
hooks, no `lod_column`). Until it ships, the options are: draw the far shell
yourself through world primitives (slow, but complete control), or accept the
engine's vanilla far terrain and hide it with fog/atmosphere.

## Atmosphere and space

`Atmosphere` models density as `exp(-h / 8500)`: half at 5.9 km, one
ten-thousandth at 78 km, vacuum by 100 km. The sky is not a native hook: it is
a **shader pack** (`.hshader`) with sky and fog stages, which already exists in
Horizon's shader API (`templates/sky`). A planet pack would draw:

* a sky gradient by view-ray altitude, with the planet's shadow at night;
* a star field that fades in above ~78 km;
* aerial perspective on terrain, strongest at the horizon;
* a sun disc and terminator.

`compute_fog_color` / `render_fog` can bias the native fog, but a full
atmosphere belongs in the pack.

## Milestones

1. **Streaming writer and world setup**: `GenerationWindow`, `ChunkStream`
   and `EngineWriter` are in the model, and `native/stream.rs` is injected into
   the server's player tick. The stream samples local columns across all six
   cube faces, so a sector edge is just another coordinate; it tracks removed
   chunks as well as added ones, and each block remembers its owning chunk so
   a seam transition cannot leave stale terrain. Column noise is cached once
   per local column (the lab measures ~40x fewer samples on a 16x16x64 chunk).
   The remaining work is compiling and testing the adapter against a Horizon
   commit, including chunk-load boundaries in-game.
   Create New World uses the same `PlanetSettings`: a `world_create` overlay
   writes a pending profile, and the first server tick stores it in
   `<save>/blocky_planet/settings.txt` and applies it to the planet.
2. **Horizon warp** (no engine changes): `world_warp` WGSL that bends the patch
   onto the sphere beyond a few hundred blocks, plus `LodPlan`-driven far
   geometry drawn through world primitives if the LOD hook stays unavailable.
3. **Atmosphere pack**: a shader pack with sky/fog/space, independent of the
   native mod and shippable on its own.
4. **Radial motion prototype**: velocity override via `entity_set_velocity` on
   an authoritative server; measure rubber-banding before promising anything.
5. **Engine hooks or raw injections**: camera roll + player motion for true
   circumnavigation; `native_lod_column` for engine-native far terrain.
6. **Frustum renderer**: only if tapered blocks are a requirement; a custom
   terrain pipeline, not a shader.

Steps 1–3 are achievable in this project with the released APIs. Steps 4–6 are
where the engine stops cooperating, and the plan should not pretend otherwise.
