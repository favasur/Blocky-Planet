// Blocky Planet's own module in the game (`crate::game::blocky_planet`):
// constants, the currently selected planet, and the text both the HUD and the
// command draw. Horizon compiles this file into `horizon-client` before the
// game is built.

use blocky_planet_core::{LocalFrame, Planet, PlanetKind, PlanetSettings, SettingsError, Vec3};
use horizon_client::{hud::HudBatch, mod_api::NativeScreenView};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub const ID: &str = "blocky_planet";
pub const NAME: &str = "Blocky Planet";
pub const VERSION: &str = "0.1.0";
pub const DESCRIPTION: &str =
    "Spherical planet of any size: 64-bit planet space with a local floating origin.";

pub const BACKGROUND: u32 = 0x7800_0000;
pub const INK: u32 = 0xFF_FFFF;

use std::sync::atomic::{AtomicU64, Ordering};

static RADIUS: AtomicU64 = AtomicU64::new(6_371_000);
static SEED: AtomicU64 = AtomicU64::new(1);
/// Bumped whenever the planet changes shape, so the streaming writer discards
/// frames and chunks sampled from the old radius or seed.
static REVISION: AtomicU64 = AtomicU64::new(0);

/// One authoritative profile for the current world and for the Create World
/// editor. The profile is stored in the save folder, so a world keeps its
/// chosen layers when it is opened again.
static SETTINGS: OnceLock<Mutex<PlanetSettings>> = OnceLock::new();
static CREATOR: OnceLock<Mutex<Creator>> = OnceLock::new();
static LOADED_WORLD: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();

const WORLD_SETTINGS: &str = "blocky_planet/settings.txt";
const PENDING_SETTINGS: &str = "pending-settings.txt";
const PANEL: u32 = 0xF0_101820;
const PANEL_EDGE: u32 = 0xFF_58A6FF;
const BUTTON: u32 = 0xFF_26384A;
const BUTTON_SELECTED: u32 = 0xFF_2F6F9E;
const TRACK: u32 = 0xFF_17222D;
const MUTED: u32 = 0xFF_A8B8C8;

struct Creator {
    open: bool,
    settings: PlanetSettings,
    status: String,
}

pub fn radius() -> u64 {
    RADIUS.load(Ordering::Relaxed)
}

pub fn set_radius(r: u64) {
    let r = r.max(64);
    if RADIUS.swap(r, Ordering::Relaxed) != r {
        REVISION.fetch_add(1, Ordering::Relaxed);
    }
    let mut settings = settings_lock().lock().unwrap_or_else(|e| e.into_inner());
    settings.diameter_km = r as f64 * 2.0 / 1000.0;
}

pub fn seed() -> u64 {
    SEED.load(Ordering::Relaxed)
}

pub fn set_seed(s: u64) {
    if SEED.swap(s, Ordering::Relaxed) != s {
        REVISION.fetch_add(1, Ordering::Relaxed);
    }
    settings_lock()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .seed = s;
}

pub fn revision() -> u64 {
    REVISION.load(Ordering::Relaxed)
}

fn settings_lock() -> &'static Mutex<PlanetSettings> {
    SETTINGS.get_or_init(|| Mutex::new(PlanetSettings::earth_default()))
}

fn creator_lock() -> &'static Mutex<Creator> {
    CREATOR.get_or_init(|| {
        Mutex::new(Creator {
            open: false,
            settings: PlanetSettings::earth_default(),
            status: String::new(),
        })
    })
}

pub fn settings_snapshot() -> PlanetSettings {
    settings_lock()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// Apply a validated profile to the running world. The profile's fractions
/// are normalized by `PlanetSettings::profile`; the radius and seed are kept
/// separately because the `/planet radius` command can produce sizes outside
/// the Create World slider's range.
pub fn apply_settings(settings: PlanetSettings) -> Result<(), SettingsError> {
    let profile = settings.profile()?;
    let diameter = settings.diameter_blocks();
    let mut guard = settings_lock().lock().unwrap_or_else(|e| e.into_inner());
    *guard = settings;
    RADIUS.store((diameter / 2).max(64), Ordering::Relaxed);
    SEED.store(guard.seed, Ordering::Relaxed);
    let _ = profile;
    REVISION.fetch_add(1, Ordering::Relaxed);
    Ok(())
}

pub fn planet() -> Planet {
    let settings = settings_snapshot();
    let profile = settings
        .profile()
        .unwrap_or_else(|_| blocky_planet_core::LayerProfile::for_kind(settings.kind));
    Planet::with_profile(radius(), seed(), profile)
}

pub fn settings_summary(settings: &PlanetSettings) -> String {
    format!("{} {:.0} km", settings.kind.label(), settings.diameter_km)
}

fn settings_path(world: &Path) -> PathBuf {
    world.join(WORLD_SETTINGS)
}

fn pending_path(instance: &Path) -> PathBuf {
    instance
        .join("horizon-mods/.horizon-mod-data/blocky_planet")
        .join(PENDING_SETTINGS)
}

/// Remember the profile for the next world creation. The world itself is
/// written when the server opens, because the save folder does not exist yet
/// while the title's Create New World screen is up.
pub fn stage_pending(instance: &Path, settings: &PlanetSettings) -> bool {
    let path = pending_path(instance);
    let Some(parent) = path.parent() else {
        return false;
    };
    fs::create_dir_all(parent).is_ok() && fs::write(path, settings.encode()).is_ok()
}

/// Load a save's profile once, when its server first ticks. A new save takes
/// the pending Create World profile; an existing save always wins over it.
pub fn ensure_world_loaded(world: Option<PathBuf>, instance: PathBuf) {
    let Some(world) = world else {
        return;
    };
    let mut loaded = LOADED_WORLD
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if loaded.as_ref() == Some(&world) {
        return;
    }

    let stored = fs::read_to_string(settings_path(&world))
        .ok()
        .and_then(|text| PlanetSettings::decode(&text).ok());
    let pending = fs::read_to_string(pending_path(&instance))
        .ok()
        .and_then(|text| PlanetSettings::decode(&text).ok());
    let settings = stored
        .or(pending)
        .unwrap_or_else(PlanetSettings::earth_default);

    if apply_settings(settings.clone()).is_ok() {
        let path = settings_path(&world);
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::write(path, settings.encode());
        let _ = fs::remove_file(pending_path(&instance));
    }
    *loaded = Some(world);
}

fn hit(rect: [f32; 4], point: [f32; 2]) -> bool {
    point[0] >= rect[0]
        && point[1] >= rect[1]
        && point[0] <= rect[0] + rect[2]
        && point[1] <= rect[1] + rect[3]
}

fn panel_rect(screen: &NativeScreenView<'_>) -> [f32; 4] {
    let [x, y, w, h] = screen.rect;
    [x + (w - 380.0) / 2.0, y + (h - 300.0) / 2.0, 380.0, 300.0]
}

fn create_button_rect(screen: &NativeScreenView<'_>) -> [f32; 4] {
    let [x, y, w, _] = screen.rect;
    // The Java prototype put its planet button in the right column, level
    // with the Game Mode section. Keep that placement and extend the label.
    [x + w - 216.0, y + 88.0, 208.0, 20.0]
}

fn type_rect(screen: &NativeScreenView<'_>, index: usize) -> [f32; 4] {
    let [x, y, _, _] = panel_rect(screen);
    [x + 12.0 + index as f32 * 120.0, y + 38.0, 108.0, 20.0]
}

fn diameter_slider_rect(screen: &NativeScreenView<'_>) -> [f32; 4] {
    let [x, y, _, _] = panel_rect(screen);
    [x + 12.0, y + 84.0, 356.0, 12.0]
}

fn advanced_rect(screen: &NativeScreenView<'_>) -> [f32; 4] {
    let [x, y, _, _] = panel_rect(screen);
    [x + 12.0, y + 108.0, 160.0, 18.0]
}

fn layer_rect(screen: &NativeScreenView<'_>, index: usize) -> [f32; 4] {
    let [x, y, _, _] = panel_rect(screen);
    [x + 12.0, y + 132.0 + index as f32 * 26.0, 356.0, 12.0]
}

fn done_rect(screen: &NativeScreenView<'_>) -> [f32; 4] {
    let [x, y, _, _] = panel_rect(screen);
    [x + 12.0, y + 262.0, 154.0, 20.0]
}

fn slider_fraction(rect: [f32; 4], mouse: [f32; 2]) -> f32 {
    ((mouse[0] - rect[0]) / rect[2]).clamp(0.0, 1.0)
}

pub fn draw_create_world(
    game: &crate::game::Game<'_>,
    batch: &mut HudBatch,
    screen: &NativeScreenView<'_>,
) {
    if !game.native_on(ID) || screen.id != "world_create" {
        return;
    }
    let mut state = creator_lock().lock().unwrap_or_else(|e| e.into_inner());
    let button = create_button_rect(screen);
    game.native_fill(batch, button, BUTTON_SELECTED);
    let label = format!("Planet: {}", settings_summary(&state.settings));
    let text_x = button[0] + (button[2] - game.native_text_width(&label)) / 2.0;
    game.native_text(batch, &label, [text_x, button[1] + 6.0], INK, true);
    if !state.open {
        return;
    }

    let panel = panel_rect(screen);
    game.native_fill(batch, panel, PANEL);
    game.native_fill(batch, [panel[0], panel[1], panel[2], 2.0], PANEL_EDGE);
    let [px, py, _, _] = panel;
    game.native_text(batch, "Planet settings", [px + 12.0, py + 10.0], INK, true);
    game.native_text(
        batch,
        "The profile is saved with the next world.",
        [px + 12.0, py + 22.0],
        MUTED,
        true,
    );

    for (i, kind) in [
        PlanetKind::EarthLike,
        PlanetKind::MarsLike,
        PlanetKind::MoonLike,
    ]
    .into_iter()
    .enumerate()
    {
        let rect = type_rect(screen, i);
        let selected = state.settings.kind == kind;
        game.native_fill(batch, rect, if selected { BUTTON_SELECTED } else { BUTTON });
        game.native_text(
            batch,
            kind.label(),
            [rect[0] + 8.0, rect[1] + 6.0],
            INK,
            true,
        );
    }

    let diameter = diameter_slider_rect(screen);
    game.native_text(
        batch,
        &format!("Diameter: {:.0} km", state.settings.diameter_km),
        [px + 12.0, py + 68.0],
        INK,
        true,
    );
    game.native_fill(batch, diameter, TRACK);
    let knob_x = diameter[0] + diameter[2] * state.settings.diameter_slider();
    game.native_fill(
        batch,
        [knob_x - 3.0, diameter[1] - 3.0, 6.0, 18.0],
        PANEL_EDGE,
    );

    let advanced = advanced_rect(screen);
    game.native_fill(batch, advanced, BUTTON);
    game.native_text(
        batch,
        if state.settings.advanced_layers {
            "[x] Advanced layers"
        } else {
            "[ ] Advanced layers"
        },
        [advanced[0] + 6.0, advanced[1] + 5.0],
        INK,
        true,
    );

    let ids = state.settings.kind.layer_ids();
    let km = state.settings.layer_km();
    for (i, id) in ids.iter().enumerate() {
        let rect = layer_rect(screen, i);
        let label = format!(
            "{}  {:.0} km",
            id.label(),
            km.get(i).copied().unwrap_or(0.0)
        );
        game.native_text(batch, &label, [px + 12.0, rect[1] - 10.0], MUTED, true);
        game.native_fill(batch, rect, TRACK);
        let knob = rect[0] + rect[2] * state.settings.layer_slider(i);
        game.native_fill(batch, [knob - 3.0, rect[1] - 2.0, 6.0, 16.0], PANEL_EDGE);
    }

    let done = done_rect(screen);
    game.native_fill(batch, done, BUTTON_SELECTED);
    game.native_text(
        batch,
        "Use for next world",
        [done[0] + 6.0, done[1] + 6.0],
        INK,
        true,
    );
    if !state.status.is_empty() {
        game.native_text(
            batch,
            &state.status,
            [done[0] + 108.0, done[1] + 6.0],
            MUTED,
            true,
        );
    }
}

pub fn click_create_world(
    game: &crate::game::Game<'_>,
    screen: &NativeScreenView<'_>,
    button: i32,
    canceled: &mut bool,
) {
    if !game.native_on(ID) || screen.id != "world_create" || button != 0 {
        return;
    }
    let mut state = creator_lock().lock().unwrap_or_else(|e| e.into_inner());
    if !state.open {
        if hit(create_button_rect(screen), screen.mouse) {
            state.open = true;
            state.status.clear();
            *canceled = true;
        }
        return;
    }

    *canceled = true;
    let panel = panel_rect(screen);
    if !hit(panel, screen.mouse) {
        state.open = false;
        return;
    }
    for (i, kind) in [
        PlanetKind::EarthLike,
        PlanetKind::MarsLike,
        PlanetKind::MoonLike,
    ]
    .into_iter()
    .enumerate()
    {
        if hit(type_rect(screen, i), screen.mouse) {
            state.settings.select_kind(kind);
            return;
        }
    }
    if hit(diameter_slider_rect(screen), screen.mouse) {
        let fraction = slider_fraction(diameter_slider_rect(screen), screen.mouse);
        state.settings.set_diameter_slider(fraction);
        return;
    }
    if hit(advanced_rect(screen), screen.mouse) {
        state.settings.advanced_layers = !state.settings.advanced_layers;
        return;
    }
    for i in 0..state.settings.kind.layer_ids().len() {
        if hit(layer_rect(screen, i), screen.mouse) {
            let fraction = slider_fraction(layer_rect(screen, i), screen.mouse);
            state.settings.set_layer_slider(i, fraction);
            return;
        }
    }
    if hit(done_rect(screen), screen.mouse) {
        let saved = stage_pending(&game.native_instance_dir(), &state.settings);
        state.open = false;
        state.status = if saved {
            "Saved for the next created world.".into()
        } else {
            "Could not write the pending profile.".into()
        };
    }
}

/// The model's inspection frame: the planet's +X surface point with local +Y
/// pointing away from the centre. The live server writer uses its own
/// per-player anchored frame and does not move this diagnostic frame.
pub fn surface_frame() -> LocalFrame {
    LocalFrame::at(Vec3::new(radius() as f64, 0.0, 0.0))
}

/// Engine (local) coordinates -> planet space, the floating-origin transform.
pub fn planet_position(x: f64, y: f64, z: f64) -> Vec3 {
    surface_frame().to_planet(Vec3::new(x, y, z))
}

/// Three HUD lines: planet, address, and the local window's scale.
pub fn hud_lines(planet: &Planet, pos: Vec3) -> Vec<String> {
    let r = pos.length();
    let address = planet.address_of(pos);
    let altitude = r - planet.radius;
    vec![
        format!(
            "{NAME} r={}  shell {}/{}",
            thousands(planet.radius as u64),
            address.shell,
            planet.shell_count()
        ),
        format!(
            "{}  i {}  j {}  layer {}",
            address.sector.name(),
            thousands_u128(address.i),
            thousands_u128(address.j),
            thousands_u128(address.layer)
        ),
        format!(
            "alt {:+.0}  block {:?}  seed {}",
            altitude,
            planet.block_at(pos),
            planet.seed
        ),
    ]
}

pub fn thousands(mut n: u64) -> String {
    if n == 0 {
        return "0".into();
    }
    let mut parts = Vec::new();
    while n > 0 {
        parts.push(n % 1000);
        n /= 1000;
    }
    let mut out = String::new();
    for (i, p) in parts.iter().rev().enumerate() {
        if i > 0 {
            out.push(',');
        }
        if i == 0 {
            out.push_str(&p.to_string());
        } else {
            out.push_str(&format!("{p:03}"));
        }
    }
    out
}

pub fn thousands_u128(n: u128) -> String {
    thousands(n.min(u64::MAX as u128) as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hud_text_names_the_planet_and_the_address() {
        let planet = Planet::new(6_371_000, 1);
        let lines = hud_lines(&planet, Vec3::new(planet.radius, 0.0, 0.0));
        assert_eq!(lines.len(), 3);
        assert!(lines[0].contains("Blocky Planet"));
        assert!(lines[1].contains("East"));
        assert!(lines[2].contains("seed 1"));
    }

    #[test]
    fn the_radius_setting_has_a_floor() {
        set_radius(1);
        assert_eq!(radius(), 64);
        set_radius(500);
        assert_eq!(radius(), 500);
    }
}
