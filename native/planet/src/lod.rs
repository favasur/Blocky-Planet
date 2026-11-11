//! Horizon, atmosphere and level-of-detail planning for an Earth-sized planet.
//!
//! None of this depends on the engine. It answers three questions the renderer
//! and the streaming writer must agree on:
//!
//! * how far can the player see at this altitude ([`horizon_distance`])?
//! * how much does the surface fall away over that distance ([`surface_drop`])?
//! * which angular tile size keeps one block at about one pixel there
//!   ([`LodPlan`])?
//!
//! A block is taken as one metre, which is the scale vanilla Minecraft uses.

use std::f64::consts::FRAC_PI_2;

/// Distance to the true horizon from an eye at `eye_altitude`, on a sphere of
/// `radius`: `sqrt(2 R h + h^2)`. Both arguments are in blocks.
pub fn horizon_distance(radius: f64, eye_altitude: f64) -> f64 {
    let r = radius.max(0.0);
    let h = eye_altitude.max(0.0);
    ((r + h) * (r + h) - r * r).max(0.0).sqrt()
}

/// How far the sphere's surface falls below the tangent plane at `distance`.
///
/// This is the number that makes a flat local patch look wrong: at 1 km it is
/// 0.08 blocks, at 10 km it is 7.8, and only at 100 km does it reach 785.
pub fn surface_drop(radius: f64, distance: f64) -> f64 {
    let r = radius.max(1.0);
    let d = distance.clamp(0.0, r);
    r - (r * r - d * d).sqrt()
}

/// Exponential atmosphere, thin enough to be irrelevant locally and gone by
/// low orbit.
#[derive(Clone, Copy, Debug)]
pub struct Atmosphere {
    /// Height at which density falls to `1/e`, in blocks (8.5 km for air).
    pub scale_height: f64,
}

impl Default for Atmosphere {
    fn default() -> Self {
        Self {
            scale_height: 8_500.0,
        }
    }
}

impl Atmosphere {
    /// Fraction of sea-level density left at `altitude`.
    pub fn density(&self, altitude: f64) -> f64 {
        (-altitude.max(0.0) / self.scale_height.max(1.0)).exp()
    }

    /// Fog/haze fraction a renderer would use (the same curve today).
    pub fn haze(&self, altitude: f64) -> f64 {
        self.density(altitude)
    }

    /// Below one part in ten thousand is "space" for gameplay and sky purposes
    /// (about 78 km up).
    pub fn is_space(&self, altitude: f64) -> bool {
        self.density(altitude) < 1e-4
    }

    /// Altitude at which the atmosphere is effectively gone.
    pub fn space_altitude(&self) -> f64 {
        self.scale_height * 10_000f64.ln()
    }
}

/// One distance band drawn at one quad-sphere subdivision level.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LodRing {
    pub near: f64,
    pub far: f64,
    /// Quadtree level: `2^level` tiles per sector side.
    pub level: u32,
    /// Arc length of one tile at the surface, in blocks.
    pub tile_arc: f64,
    /// Tiles needed to cover the whole planet at this level.
    pub tiles_on_sphere: u64,
}

/// Distance bands from the player out to the horizon.
#[derive(Clone, Debug)]
pub struct LodPlan {
    pub horizon: f64,
    pub rings: Vec<LodRing>,
}

impl LodPlan {
    /// Plan for one eye position.
    ///
    /// `fov_degrees` and `pixels` set the angular size of a pixel; a tile is
    /// chosen so it lands near that size at the ring's distance. Near rings get
    /// the finest levels; the horizon gets coarse tiles, which is exactly the
    /// "vertically dynamic" part: the higher the eye, the farther the horizon,
    /// and the more coarse rings are needed.
    pub fn for_eye(radius: f64, eye_altitude: f64, fov_degrees: f64, pixels: u32) -> Self {
        let r = radius.max(1.0);
        let horizon = horizon_distance(r, eye_altitude).max(64.0);
        let angular =
            (fov_degrees.clamp(10.0, 170.0).to_radians() / pixels.max(1) as f64).max(1e-7);
        let max_level = max_level_for(r);

        let mut rings: Vec<LodRing> = Vec::new();
        let mut near = 16.0f64;
        while near < horizon * 1.05 {
            let far = (near * 2.0).min(horizon.max(near + 1.0));
            let level = level_for(r, near, angular, max_level);
            let tile_arc = tile_arc(r, level);
            let tiles_on_sphere = 6u64.saturating_mul(4u64.saturating_pow(level.min(24)));

            match rings.last_mut() {
                Some(last) if last.level == level => last.far = far,
                _ => rings.push(LodRing {
                    near,
                    far,
                    level,
                    tile_arc,
                    tiles_on_sphere,
                }),
            }

            if far <= near {
                break;
            }
            near = far;
        }

        Self { horizon, rings }
    }

    /// Finest level (largest number) used anywhere in the plan.
    pub fn finest_level(&self) -> u32 {
        self.rings.iter().map(|r| r.level).max().unwrap_or(0)
    }

    /// Coarsest level (nearest the horizon).
    pub fn coarsest_level(&self) -> u32 {
        self.rings.iter().map(|r| r.level).min().unwrap_or(0)
    }
}

/// Tiles per sector side: `2^level`, with a floor of half a block per tile.
pub fn max_level_for(radius: f64) -> u32 {
    let side = radius.max(1.0) * FRAC_PI_2;
    let level = (side / 0.5).log2().floor();
    level.clamp(0.0, 24.0) as u32
}

pub fn tile_arc(radius: f64, level: u32) -> f64 {
    radius.max(1.0) * FRAC_PI_2 / 2f64.powi(level.min(24) as i32)
}

fn level_for(radius: f64, distance: f64, angular: f64, max_level: u32) -> u32 {
    let side = radius.max(1.0) * FRAC_PI_2;
    let wanted = side / (distance.max(1.0) * angular);
    wanted.log2().floor().clamp(0.0, max_level as f64) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    const EARTH: f64 = 6_371_000.0;

    #[test]
    fn the_horizon_grows_with_altitude() {
        assert_eq!(horizon_distance(EARTH, 0.0), 0.0);
        let at_2m = horizon_distance(EARTH, 2.0);
        assert!((at_2m - 5_048.0).abs() < 5.0, "2 m eye: {at_2m}");
        let at_100km = horizon_distance(EARTH, 100_000.0);
        assert!(
            (at_100km - 1_133_226.0).abs() < 200.0,
            "100 km eye: {at_100km}"
        );
        assert!(at_100km > at_2m * 200.0);
    }

    #[test]
    fn curvature_is_invisible_locally_and_dominant_far_away() {
        assert!(surface_drop(EARTH, 1_000.0) < 0.1);
        assert!((surface_drop(EARTH, 100_000.0) - 785.0).abs() < 5.0);
        // The drop over the (flat) render distance is far below one block.
        assert!(surface_drop(EARTH, 1_024.0) < 0.2);
    }

    #[test]
    fn the_atmosphere_thins_to_space() {
        let a = Atmosphere::default();
        assert_eq!(a.density(0.0), 1.0);
        let half = a.scale_height * 2f64.ln();
        assert!((a.density(half) - 0.5).abs() < 1e-9);
        assert!(!a.is_space(10_000.0));
        assert!(a.is_space(100_000.0));
        assert!((a.space_altitude() - 78_300.0).abs() < 500.0);
    }

    #[test]
    fn lod_gets_coarser_towards_the_horizon() {
        let plan = LodPlan::for_eye(EARTH, 2.0, 70.0, 1080);
        assert!(!plan.rings.is_empty());
        assert!(plan.horizon > 5_000.0);
        assert!(plan.finest_level() >= plan.coarsest_level());

        // Near rings are finer (smaller tiles); rings never become finer as
        // distance grows.
        for w in plan.rings.windows(2) {
            assert!(w[0].level >= w[1].level, "{:?} then {:?}", w[0], w[1]);
            assert!(w[0].tile_arc <= w[1].tile_arc + 1e-9);
        }

        let last = plan.rings.last().unwrap();
        assert!(last.far >= plan.horizon * 0.99);
        assert!(plan
            .rings
            .iter()
            .all(|r| r.tile_arc.is_finite() && r.far > r.near));
    }

    #[test]
    fn a_higher_eye_needs_a_deeper_plan() {
        let ground = LodPlan::for_eye(EARTH, 2.0, 70.0, 1080);
        let orbit = LodPlan::for_eye(EARTH, 100_000.0, 70.0, 1080);
        assert!(orbit.horizon > ground.horizon);
        assert!(orbit.rings.last().unwrap().far > ground.rings.last().unwrap().far);
    }
}
