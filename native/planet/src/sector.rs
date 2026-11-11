//! The six cube faces of the quad sphere.
//!
//! Each sector owns a right-handed tangent basis `(u, v)` with
//! `u x v = normal`, so the same local (u, v) code works on every face. The
//! choice of which world axis becomes u or v is what glues neighbouring faces
//! together; it is arbitrary but must stay fixed once chosen, because block
//! indices at a seam are swapped or flipped between two faces.

use crate::vec::Vec3;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Sector {
    PosX,
    NegX,
    PosY,
    NegY,
    PosZ,
    NegZ,
}

pub const ALL_SECTORS: [Sector; 6] = [
    Sector::PosX,
    Sector::NegX,
    Sector::PosY,
    Sector::NegY,
    Sector::PosZ,
    Sector::NegZ,
];

impl Sector {
    pub fn index(self) -> usize {
        ALL_SECTORS.iter().position(|s| *s == self).unwrap()
    }

    pub fn from_index(i: usize) -> Sector {
        ALL_SECTORS[i % 6]
    }

    /// Name used in the HUD and in logs.
    pub fn name(self) -> &'static str {
        match self {
            Sector::PosX => "East",
            Sector::NegX => "West",
            Sector::PosY => "Up",
            Sector::NegY => "Down",
            Sector::PosZ => "South",
            Sector::NegZ => "North",
        }
    }

    pub fn normal(self) -> Vec3 {
        match self {
            Sector::PosX => Vec3::new(1.0, 0.0, 0.0),
            Sector::NegX => Vec3::new(-1.0, 0.0, 0.0),
            Sector::PosY => Vec3::new(0.0, 1.0, 0.0),
            Sector::NegY => Vec3::new(0.0, -1.0, 0.0),
            Sector::PosZ => Vec3::new(0.0, 0.0, 1.0),
            Sector::NegZ => Vec3::new(0.0, 0.0, -1.0),
        }
    }

    /// Horizontal axis of the face's grid.
    pub fn u_axis(self) -> Vec3 {
        match self {
            Sector::PosX => Vec3::new(0.0, 0.0, -1.0),
            Sector::NegX => Vec3::new(0.0, 0.0, 1.0),
            Sector::PosY => Vec3::new(1.0, 0.0, 0.0),
            Sector::NegY => Vec3::new(1.0, 0.0, 0.0),
            Sector::PosZ => Vec3::new(1.0, 0.0, 0.0),
            Sector::NegZ => Vec3::new(-1.0, 0.0, 0.0),
        }
    }

    /// Vertical axis of the face's grid.
    pub fn v_axis(self) -> Vec3 {
        match self {
            Sector::PosX => Vec3::new(0.0, 1.0, 0.0),
            Sector::NegX => Vec3::new(0.0, 1.0, 0.0),
            Sector::PosY => Vec3::new(0.0, 0.0, -1.0),
            Sector::NegY => Vec3::new(0.0, 0.0, 1.0),
            Sector::PosZ => Vec3::new(0.0, 1.0, 0.0),
            Sector::NegZ => Vec3::new(0.0, 1.0, 0.0),
        }
    }

    /// Sector and cube-face coordinates in `[-1, 1]` for a planet-space point.
    ///
    /// The dominant coordinate names the face; the other two, divided by it,
    /// are the face coordinates. This is the cube-face projection the article
    /// calls the reverse projection, and it is exact for any radius.
    pub fn from_planet(p: Vec3) -> (Sector, f64, f64) {
        let (axis, value) = p.dominant();
        let sector = match (axis, value >= 0.0) {
            (0, true) => Sector::PosX,
            (0, false) => Sector::NegX,
            (1, true) => Sector::PosY,
            (1, false) => Sector::NegY,
            (2, true) => Sector::PosZ,
            _ => Sector::NegZ,
        };
        let n = sector.normal();
        let d = p.dot(n);
        // d is positive by construction, but guard the centre of the planet.
        if d <= 0.0 {
            return (sector, 0.0, 0.0);
        }
        let u = (p.dot(sector.u_axis()) / d).clamp(-1.0, 1.0);
        let v = (p.dot(sector.v_axis()) / d).clamp(-1.0, 1.0);
        (sector, u, v)
    }

    /// Unit direction for face coordinates `(u, v)`: the cube point is pushed
    /// out onto the sphere, which is the "inflate the cube" step.
    pub fn direction(self, u: f64, v: f64) -> Vec3 {
        (self.normal() + self.u_axis() * u + self.v_axis() * v).normalized()
    }

    /// Planet-space point at face coordinates `(u, v)` and radius `r`.
    pub fn position(self, u: f64, v: f64, r: f64) -> Vec3 {
        self.direction(u, v) * r
    }
}
