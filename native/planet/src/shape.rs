//! Block *shape* at depth.
//!
//! On a sphere a block is not a cube: it is a spherical frustum whose inner
//! face is narrower than its outer face. The article's price for keeping
//! blocks aligned is exactly this distortion, and the price grows as you dig:
//! inside one shell, width scales with radius, so a block spans up to
//! `outer / inner` in width. That ratio is bounded by construction (shells
//! double thickness and resolution together), but it is *not* bounded at the
//! planet centre, where the radius goes to zero and every wedge collapses.
//!
//! This module makes that contract explicit and testable:
//!
//! * every block is a frustum with eight real corner points;
//! * a shape is accepted only if it is finite, its shortest edge is at least
//!   [`MIN_EDGE`] blocks, and its longest/shortest edge ratio is at most
//!   [`MAX_ASPECT`];
//! * below [`MIN_SHELL_RADIUS`] no frustum is used at all — the core is a
//!   Cartesian grid of unit cubes, so there is no division by a zero radius and
//!   no zero-width geometry to hand to collision or the mesher.
//!
//! A rejected shape is not an error: `geometry_at` returns `None`, and the
//! engine draws and collides nothing there. That is what keeps a bad number
//! from becoming a crash or a NaN in the renderer.

use crate::address::BlockAddress;
use crate::sector::Sector;
use crate::shell::ShellModel;
use crate::vec::Vec3;

/// Below this radius the shell frustums are replaced by unit cubes: shell 0
/// starts at radius zero, where every wedge has zero width.
pub const MIN_SHELL_RADIUS: f64 = 16.0;
/// A shape narrower than this anywhere is not handed to the game.
pub const MIN_EDGE: f64 = 0.1;
/// Longest edge / shortest edge. Shell 1 is the worst case at 3.0 (16 -> 48);
/// shells from 2 outward stay under 2.4. A larger ratio means bad input, not a
/// planet.
pub const MAX_ASPECT: f64 = 3.5;

/// Exact geometry bounds for one block: a frustum of a spherical sector.
#[derive(Clone, Copy, Debug)]
pub struct BlockShape {
    pub sector: Sector,
    pub shell: u32,
    pub inner_radius: f64,
    pub outer_radius: f64,
    /// Cube-face coordinates of the cell, in `[-1, 1]`.
    pub u0: f64,
    pub u1: f64,
    pub v0: f64,
    pub v1: f64,
}

impl BlockShape {
    /// Geometry for an address. Never panics; use [`BlockShape::is_valid`] to
    /// decide whether the result may be used.
    pub fn from_address(shells: &ShellModel, a: &BlockAddress) -> Self {
        let n = shells.resolution(a.shell) as f64;
        let inner = shells.inner_radius(a.shell) + a.layer as f64;
        let outer = inner + 1.0;
        let du = 2.0 / n;
        let u0 = -1.0 + a.i as f64 * du;
        let v0 = -1.0 + a.j as f64 * du;
        Self {
            sector: a.sector,
            shell: a.shell,
            inner_radius: inner,
            outer_radius: outer,
            u0,
            u1: u0 + du,
            v0,
            v1: v0 + du,
        }
    }

    /// Eight corners: the four inner ones, then the four outer ones.
    pub fn corners(&self) -> [Vec3; 8] {
        let s = self.sector;
        let (u0, u1, v0, v1) = (self.u0, self.u1, self.v0, self.v1);
        [
            s.position(u0, v0, self.inner_radius),
            s.position(u1, v0, self.inner_radius),
            s.position(u1, v1, self.inner_radius),
            s.position(u0, v1, self.inner_radius),
            s.position(u0, v0, self.outer_radius),
            s.position(u1, v0, self.outer_radius),
            s.position(u1, v1, self.outer_radius),
            s.position(u0, v1, self.outer_radius),
        ]
    }

    /// The twelve edge lengths: four inner, four outer, four radial.
    pub fn edges(&self) -> [f64; 12] {
        let c = self.corners();
        let mut e = [0.0; 12];
        for i in 0..4 {
            e[i] = (c[(i + 1) % 4] - c[i]).length(); // inner ring
            e[4 + i] = (c[4 + (i + 1) % 4] - c[4 + i]).length(); // outer ring
            e[8 + i] = (c[4 + i] - c[i]).length(); // radial, exactly 1 layer
        }
        e
    }

    pub fn min_edge(&self) -> f64 {
        self.edges().iter().copied().fold(f64::INFINITY, f64::min)
    }

    pub fn max_edge(&self) -> f64 {
        self.edges().iter().copied().fold(0.0, f64::max)
    }

    pub fn aspect(&self) -> f64 {
        let min = self.min_edge();
        if min <= 0.0 {
            f64::INFINITY
        } else {
            self.max_edge() / min
        }
    }

    /// Width of the bottom face along u, in blocks.
    pub fn width_inner(&self) -> f64 {
        let s = self.sector;
        (s.position(self.u1, self.v0, self.inner_radius)
            - s.position(self.u0, self.v0, self.inner_radius))
        .length()
    }

    /// Width of the top face along u, in blocks.
    pub fn width_outer(&self) -> f64 {
        let s = self.sector;
        (s.position(self.u1, self.v0, self.outer_radius)
            - s.position(self.u0, self.v0, self.outer_radius))
        .length()
    }

    /// The gate every shape must pass before the game sees it.
    pub fn is_valid(&self) -> bool {
        if !(self.inner_radius.is_finite()
            && self.outer_radius.is_finite()
            && self.outer_radius > self.inner_radius)
        {
            return false;
        }
        let c = self.corners();
        if c.iter().any(|p| !p.is_finite()) {
            return false;
        }
        let min = self.min_edge();
        let aspect = self.aspect();
        min.is_finite() && aspect.is_finite() && min >= MIN_EDGE && aspect <= MAX_ASPECT
    }
}

/// What the game can receive for a solid block.
#[derive(Clone, Copy, Debug)]
pub enum BlockGeometry {
    /// A shell block: a validated frustum.
    Frustum(BlockShape),
    /// A core block: a unit cube, axis-aligned in planet space.
    CoreCube { min: Vec3, max: Vec3 },
}

impl BlockGeometry {
    pub fn is_valid(&self) -> bool {
        match self {
            BlockGeometry::Frustum(s) => s.is_valid(),
            BlockGeometry::CoreCube { min, max } => {
                min.is_finite()
                    && max.is_finite()
                    && (max.x - min.x) == 1.0
                    && (max.y - min.y) == 1.0
                    && (max.z - min.z) == 1.0
            }
        }
    }

    pub fn min_edge(&self) -> f64 {
        match self {
            BlockGeometry::Frustum(s) => s.min_edge(),
            BlockGeometry::CoreCube { .. } => 1.0,
        }
    }

    pub fn aspect(&self) -> f64 {
        match self {
            BlockGeometry::Frustum(s) => s.aspect(),
            BlockGeometry::CoreCube { .. } => 1.0,
        }
    }

    pub fn describe(&self) -> String {
        match self {
            BlockGeometry::Frustum(s) => format!(
                "frustum {:.2}->{:.2} wide, {:.2} tall, aspect {:.2}",
                s.width_inner(),
                s.width_outer(),
                s.outer_radius - s.inner_radius,
                s.aspect()
            ),
            BlockGeometry::CoreCube { .. } => "core cube 1.00 wide, aspect 1.00".to_string(),
        }
    }
}
