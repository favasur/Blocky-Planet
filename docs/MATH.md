# Blocky Planet — the math

Reference: <https://bowerbyte.com/posts/blocky-planet/> ("Blocky Planet —
Making Minecraft Spherical").

## 1. Quad sphere

A cube face has coordinates `(u, v) ∈ [-1, 1]`. The cube point
`n + u·û + v·v̂` is normalized onto the sphere:

```
direction(u, v) = normalize(n + u·û + v·v̂)
position(u, v, r) = r · direction(u, v)
```

The reverse projection for a planet point `p`:

```
axis  = argmax(|p.x|, |p.y|, |p.z|)      → one of 6 sectors
d     = p · n                            (positive by construction)
u     = (p · û) / d
v     = (p · v̂) / d
```

Each sector's basis is right-handed (`û × v̂ = n`), so one code path serves all
six faces. Neighbouring faces swap or flip `u`/`v`; the test
`a_seam_maps_to_the_neighbouring_sector` pins the flip at the +X/+Z edge.

Why not latitude/longitude: an equirectangular map compresses blocks near the
poles and has a singularity there. A quad sphere has neither.

## 2. Shells

The Java version used `resolution(k) = 16 · 2^k` with a fixed 16-block shell
thickness. At Earth radius that is shell 398,187, and `16 · 2^398187` overflows
`int` at `k = 28` — the mod could not work above ~450 blocks.

The article's own scheme doubles **layers per shell too**:

```
inner(k)       = L₀ · (2^k − 1)
layers(k)      = L₀ · 2^k
resolution(k)  = N₀ · 2^k
```

With `L₀ = 16`, `N₀ = 25`:

```
r = 6,371,000  →  k = 18
inner(18) = 16 · (2^18 − 1) = 4,194,288
layers(18) = 4,194,304        (reaches 8,388,592)
resolution(18) = 25 · 2^18 = 6,553,600 blocks per face side
```

Block width at the bottom of a shell:

```
w = (π/2 · inner(k)) / resolution(k)
  = (π/2 · 16 · (2^k − 1)) / (25 · 2^k)
  → (π/2 · 16) / 25 = 1.005 layers
```

so a block is ~1×1×1. At the top of the shell it is ~2 wide for 1 tall; the
article accepts that trade for edge alignment. Because the index grows like
`log₂(r/L₀)`, the shell count for 9e18 blocks is still under 60, and all
arithmetic is `u128`/`f64`, so nothing overflows.

## 2.1 Block shape at depth, and the core

A shell block is the spherical cell

```
inner = inner(k) + layer        (one layer tall)
outer = inner + 1
angle: [u0, u0 + 2/N_k] x [v0, v0 + 2/N_k]
```

Its eight corners are `sector.position(u, v, r)` for `u ∈ {u0,u1}`,
`v ∈ {v0,v1}`, `r ∈ {inner,outer}`. Its width is the chord between the two
inner corners, so:

```
width(r) ≈ (π/2 · r) / N_k
aspect   = outer / inner ≤ 3      (worst case: shell 1, 16 → 48)
```

From shell 2 outward the aspect stays below 2.4. A block's height is always one
layer; only width changes, which is the article's "constant block height".

Accepted shapes must satisfy

```
all corners finite
min edge ≥ 0.1
max edge / min edge ≤ 3.5
outer > inner
```

Anything else returns `None` — the engine draws and collides nothing there.

**The centre.** Shell 0 starts at `r = 0`, and every cell there is a wedge of
zero width. The model therefore does not use the shell grid below
`MIN_SHELL_RADIUS = 16`: that region is a Cartesian `1x1x1` grid in planet
space (a cube tier), addressed by `CoreAddress`. There is no division by a zero
radius and no zero-width cell. The planet is solid to the centre, so this tier
is the floor that makes a real core possible without degenerate wedges.

At `r = 16` the shell width is about one block, and it grows in proportion to
radius; the chord width is 1.48 blocks at r=64, 1.45 at r=500 and 2.26 in
Earth's shell 17. Those are the thinnest cells a solid interior can produce,
and they pass the shape contract above.

**Neighbour topology.** At the top layer of shell `k`, the next shell has twice
the resolution, so one block maps to the 2x2 group

```
(i, j) → (2i, 2j), (2i+1, 2j), (2i, 2j+1), (2i+1, 2j+1)   at layer 0, shell k+1
```

and the inverse collapses four to one. Structures must be placed within one
shell; the article's shrinking roof is this factor. At a sector corner three
faces meet; the dominant-axis tie-break gives the point to exactly one sector,
so a block cannot be written three times.

## 3. Address

```
BlockAddress { sector, shell, i, j, layer }
i      = floor((u + 1)/2 · N_k)        clamped to N_k − 1
j      = floor((v + 1)/2 · N_k)
layer  = floor(r − inner(k))           clamped to layers(k) − 1
chunk  = (i/16, layer/16, j/16)
block  = (i%16, layer%16, j%16)
centre = sector.position((i+0.5)/N_k · 2 − 1,
                         (j+0.5)/N_k · 2 − 1,
                         inner(k) + layer + 0.5)
```

`block_addresses_round_trip_at_planetary_scale` checks `address → centre →
address` at Earth scale for all six sectors.

## 4. Terrain on a sphere

Flat worlds sample a 2D height map. A sphere has no seamless 2D chart, so the
surface height is a 3D function evaluated at the surface direction:

```
surface(dir) = R + A · fbm(dir · 3, seed, 4),   A = 64 blocks
```

The direction is a unit vector, so the noise wraps perfectly: one ocean, no
seam. The planet is solid, with real layers as fractions of the radius:

| layer | Earth thickness | fraction | volume share |
| --- | --- | --- | --- |
| crust | 20 km | 0.00314 | ~0.9% |
| mantle | 2,871 km | 0.4507 | ~83% |
| outer core | 2,260 km | 0.3547 | ~15.6% |
| inner core | 1,220 km | 0.1915 | ~0.7% |

`LayerProfile::layer_at(r / R)` classifies a radius in O(1). The top four
blocks are the surface block (grass, dirt or stone) and its subsurface block;
below that the layer block applies. Profile fractions are normalized, so a
custom thickness cannot leave a gap or an overlap.

A full column is never generated. The surface band is 256 blocks, or the band
around the player when underground; `LocalWindow::checked` refuses anything
above 4,194,304 blocks. `Column` stores the surface once per column so noise is
sampled per column, not per block. The Java version's 3.26-billion-iteration
chunk cannot be expressed.

## 5. Gravity

```
direction(p) = −p / |p|
magnitude(r) = g₀                     if r ≥ R
             = g₀ · r / R             if r < R
```

Zero at the centre, constant on and above the surface. The player's local up is
`+normalize(p)`, so the camera can stay level with the ground even on the
underside of the planet.

## 6. Floating origin

```
to_local(p)   = ( (p−o)·ê, (p−o)·û, (p−o)·n̂ )
to_planet(l)  = o + ê·l.x + û·l.y + n̂·l.z
```

`o` is the local origin (the player's feet), `û = normalize(o)` is local up.
`o` is rebased whenever the player leaves the window. Local coordinates stay in
the range a 32-bit engine can draw precisely; planet coordinates stay in `f64`,
which is exact for integers up to 2⁵³ (~9e15 blocks) and still usable beyond
that.

## 7. Worked numbers (Earth-sized)

| quantity | value |
| --- | --- |
| radius | 6,371,000 blocks |
| surface shell | 18 (of 19) |
| blocks per face side | 6,553,600 |
| chunks per face side | 409,600 |
| chunks in the surface shell | 409,600² × 262,144 ≈ 4.4 × 10¹⁶ sparse slots |
| block width at shell bottom | 1.005 blocks |
| local window the engine holds | 33 × 49 × 33 ≈ 53,000 blocks |
| generated surface band | 256 blocks (0.004% of the radius) |
| solid blocks in a column | ~6.37 million (classified, never walked) |
| planet blocks the engine holds | 0 (it holds only the window) |
