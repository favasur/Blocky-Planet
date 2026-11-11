// Blocky Planet's own module in the game (`crate::game::blocky_planet`):
// constants, the currently selected planet, and the text both the HUD and the
// command draw. Horizon compiles this file into `horizon-client` before the
// game is built.

use blocky_planet_core::{LocalFrame, Planet, Vec3};

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

pub fn radius() -> u64 {
    RADIUS.load(Ordering::Relaxed)
}

pub fn set_radius(r: u64) {
    let r = r.max(64);
    if RADIUS.swap(r, Ordering::Relaxed) != r {
        REVISION.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn seed() -> u64 {
    SEED.load(Ordering::Relaxed)
}

pub fn set_seed(s: u64) {
    if SEED.swap(s, Ordering::Relaxed) != s {
        REVISION.fetch_add(1, Ordering::Relaxed);
    }
}

pub fn revision() -> u64 {
    REVISION.load(Ordering::Relaxed)
}

pub fn planet() -> Planet {
    Planet::new(radius(), seed())
}

/// The surface frame the engine box is anchored to: the planet's +X surface
/// point, with local +Y pointing away from the centre. A real streaming
/// implementation moves this anchor as the player travels; until then it is
/// the fixed point where the model can be inspected in game.
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
