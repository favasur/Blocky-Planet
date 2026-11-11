//! The local, gravity-aligned frame the game engine sees.
//!
//! This is the piece that removes the world-height limit in practice. The
//! planet is kept in 64-bit planet space; the engine is handed a small box
//! around the player, rotated so local +Y is "up" (away from the planet
//! centre). When the player travels far enough, the origin is rebased — a
//! floating origin — and only `f32`-sized local coordinates ever reach the
//! renderer or the physics.

use crate::vec::Vec3;

#[derive(Clone, Copy, Debug)]
pub struct LocalFrame {
    /// Planet-space position of the local origin (the player's feet, usually).
    pub origin: Vec3,
    /// Local +X: east on the tangent plane.
    pub east: Vec3,
    /// Local +Y: up, away from the planet centre.
    pub up: Vec3,
    /// Local +Z: north on the tangent plane.
    pub north: Vec3,
}

impl LocalFrame {
    /// A frame standing at a planet-space point, with up along the radius.
    pub fn at(origin: Vec3) -> Self {
        let up = origin.normalized();
        // Any reference not parallel to up gives a stable basis. Switching at
        // the poles avoids a degenerate cross product.
        let reference = if up.y.abs() < 0.9 { Vec3::Y } else { Vec3::X };
        let east = reference.cross(up).normalized();
        let north = up.cross(east);
        Self {
            origin,
            east,
            up,
            north,
        }
    }

    /// A frame anchored at `planet` but whose local coordinates place `engine`
    /// at the same position: `to_local(planet) == engine`. This is how a
    /// floating-origin rebase keeps the player's engine coordinates fixed
    /// while changing the radial up direction.
    pub fn anchored(planet: Vec3, engine: Vec3) -> Self {
        let up = planet.normalized();
        let reference = if up.y.abs() < 0.9 { Vec3::Y } else { Vec3::X };
        let east = reference.cross(up).normalized();
        let north = up.cross(east);
        let origin = planet - east * engine.x - up * engine.y - north * engine.z;
        Self {
            origin,
            east,
            up,
            north,
        }
    }

    /// Planet-space point -> local coordinates, in blocks.
    pub fn to_local(&self, p: Vec3) -> Vec3 {
        let d = p - self.origin;
        Vec3::new(d.dot(self.east), d.dot(self.up), d.dot(self.north))
    }

    /// Local coordinates -> planet-space point.
    pub fn to_planet(&self, local: Vec3) -> Vec3 {
        self.origin + self.east * local.x + self.up * local.y + self.north * local.z
    }

    /// Whole-block planet position of a local block coordinate.
    pub fn to_planet_block(&self, local: [i32; 3]) -> Vec3 {
        self.to_planet(Vec3::new(local[0] as f64, local[1] as f64, local[2] as f64))
    }

    /// Whether a local position has drifted far enough to rebase.
    pub fn needs_rebase(&self, local: Vec3, max_radius: f64) -> bool {
        local.x.abs() > max_radius || local.z.abs() > max_radius
    }

    /// Gravity in local coordinates (downwards: -Y), for the engine's physics.
    pub fn local_gravity(&self) -> Vec3 {
        Vec3::new(0.0, -1.0, 0.0)
    }
}
