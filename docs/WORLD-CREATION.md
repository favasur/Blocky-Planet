# World creation

The order the player sees when creating a Blocky Planet world:

1. **Planet type**
   * Earth-like (default)
   * Mars-like
   * Moon-like

   Each type supplies its own layer count, default thicknesses, surface block
   and slider ranges.

2. **Diameter slider**
   * Range: **1,000 – 162,000 km**, logarithmic.
   * Default: **12,742 km**, Earth's diameter and within 0.1% of the
     geometric centre of the range (`sqrt(1,000 × 162,000) ≈ 12,728 km`).
   * The model stores the radius in blocks at **1 block = 1 m** (12,742 km =
     12,742,000 blocks).

3. **Advanced planet layer settings** (unchecked by default)
   * When checked, one slider per layer appears below the diameter slider.
   * Defaults are the real body's values; a type switch resets them.
   * Sliders display thickness in km at the current diameter. Internally the
     model stores **fractions of the radius**, so changing the diameter keeps
     the same proportions and any positive set of values is normalized to sum
     to 1 (no gaps, no overlaps).

## Defaults and sliders

| Type | Layer | Default | Slider |
| --- | --- | --- | --- |
| Earth-like | Crust | 20 km | 5 – 70 km |
| Earth-like | Mantle | 2,871 km | 500 – 4,000 km |
| Earth-like | Outer core | 2,260 km | 500 – 3,000 km |
| Earth-like | Inner core | 1,220 km | 100 – 2,000 km |
| Mars-like | Crust | 50 km | 10 – 100 km |
| Mars-like | Mantle | 1,510 km | 500 – 3,500 km |
| Mars-like | Core | 1,830 km | 500 – 2,500 km |
| Moon-like | Crust | 40 km | 5 – 100 km |
| Moon-like | Mantle | 1,457 km | 500 – 2,300 km |
| Moon-like | Core | 240 km | 50 – 800 km |

The Earth defaults above produce the real volume shares: crust **~0.9%**,
mantle **~83%**, outer core **~15.6%**, inner core **~0.7%**. The crust is
20 km rather than 35 km because that is the average of oceanic and continental
crust, and it matches the ~1% volume figure.

## Model status

`PlanetSettings` in `native/planet/src/layers.rs` already encodes this exact
order:

```rust
pub struct PlanetSettings {
    pub kind: PlanetKind,
    pub diameter_km: f64,
    pub advanced_layers: bool,
    pub layer_fractions: Vec<f64>,
    pub seed: u64,
}
```

`Planet::from_settings` builds the planet and validates the diameter, layer
count and positive thicknesses.

## In-game layout

The native bundle adds a `Planet: …` row to the title's `world_create` screen
(Horizon API 326). Its overlay follows the order above:

* three body buttons (Earth-like, Mars-like, Moon-like);
* a logarithmic diameter slider, with the current diameter in km;
* an `Advanced layers` toggle and one thickness slider per layer;
* `Use for next world`, which stores the profile as pending.

While the save does not exist yet, the chosen profile is staged in
`horizon-mods/.horizon-mod-data/blocky_planet/pending-settings.txt`. On the
first server tick of the new world it is moved into
`<save>/blocky_planet/settings.txt`. Reopening a save always reads that file
and ignores any pending profile. The format is line based (`version`, `kind`,
`diameter_km`, `advanced_layers`, `seed`, then `layer_n` fractions) so the
native crate needs no serialization dependency; `PlanetSettings::decode`
validates it again.

The overlay and persistence are written against Horizon's documented screen,
input and world-folder APIs. The native `.hnmod` still needs its first full
game build before this is claimed as in-game verified.
