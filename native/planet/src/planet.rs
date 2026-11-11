//! The planet itself: radius, terrain and block lookup, with no upper bound.
//!
//! A radius is an `f64` over a `u64` input, so a planet may be a 500-block
//! moon or 9.2e18 blocks across; the shell model only needs ~log2(r) shells.
//!
//! # Why this does not freeze
//!
//! The failed Java version iterated every Y from the surface to the core and
//! called noise per block: 12.7 million Y values times 256 columns, 3.26
//! billion iterations per chunk. The planet is not solid, so that work was
//! almost entirely air.
//!
//! Here the solid part is a set of **fixed-thickness bands**, as in the
//! article's structure: a crust (at most 128 blocks), a Nether ring (at most
//! 256 thick), and an 8-block core shell. Everything between them is void.
//! A whole column therefore has a few hundred solid blocks regardless of the
//! radius, and [`Planet::column_plan`] computes the surface noise **once per
//! column** instead of once per block.

use crate::address::{AddressSpace, BlockAddress, CoreAddress};
use crate::noise;
use crate::shape::{BlockGeometry, BlockShape, MIN_SHELL_RADIUS};
use crate::shell::ShellModel;
use crate::vec::Vec3;

/// Salt for the Nether ring's noise, so it does not echo the surface.
const NETHER_SALT: u64 = 0x4E45_5448_4552_0001;

/// Thickest the surface crust ever gets, in blocks.
pub const MAX_CRUST: f64 = 128.0;
/// Thickness of the solid bedrock shell around the hollow core.
pub const CORE_SHELL: f64 = 8.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Block {
    Air,
    Water,
    Grass,
    Dirt,
    Stone,
    Deepslate,
    Bedrock,
    Netherrack,
    Lava,
}

impl Block {
    pub fn ascii(self) -> char {
        match self {
            Block::Air => ' ',
            Block::Water => '~',
            Block::Grass => '"',
            Block::Dirt => '.',
            Block::Stone => '#',
            Block::Deepslate => 'D',
            Block::Bedrock => 'B',
            Block::Netherrack => 'N',
            Block::Lava => 'L',
        }
    }

    pub fn is_solid(self) -> bool {
        !matches!(self, Block::Air | Block::Water | Block::Lava)
    }
}

/// Radial solid bands for one column, computed once.
#[derive(Clone, Copy, Debug)]
pub struct ColumnPlan {
    pub surface: f64,
    pub sea: f64,
    /// `(inner, outer)` of the surface crust.
    pub crust: (f64, f64),
    /// `(inner, outer)` of the Nether ring, when it fits below the crust.
    pub ring: Option<(f64, f64)>,
    /// `(inner, outer)` of the solid core shell above the hollow centre.
    pub core: Option<(f64, f64)>,
}

impl ColumnPlan {
    /// Solid radial spans, outermost first.
    pub fn solid_spans(&self) -> Vec<(f64, f64)> {
        let mut spans = vec![self.crust];
        if let Some(r) = self.ring {
            spans.push(r);
        }
        if let Some(c) = self.core {
            spans.push(c);
        }
        spans
    }

    /// Total solid thickness of the column. Independent of the planet radius,
    /// which is the whole point: a few hundred blocks, not millions.
    pub fn solid_thickness(&self) -> f64 {
        self.solid_spans()
            .iter()
            .map(|(inner, outer)| (outer - inner).max(0.0))
            .sum()
    }

    /// Classify a radius in this column without resampling the surface noise.
    pub fn block_at(&self, seed: u64, dir: Vec3, r: f64) -> Block {
        if r > self.surface {
            return if r <= self.sea {
                Block::Water
            } else {
                Block::Air
            };
        }

        if r >= self.crust.0 && r <= self.crust.1 {
            let depth = self.surface - r;
            return if depth < 1.0 {
                Block::Grass
            } else if depth < 4.0 {
                Block::Dirt
            } else if depth < 96.0 {
                Block::Stone
            } else {
                Block::Deepslate
            };
        }

        if let Some((inner, outer)) = self.ring {
            if r >= inner && r <= outer {
                let n = noise::fbm(dir * 12.0, seed ^ NETHER_SALT, 3);
                return if n > 0.45 {
                    Block::Lava
                } else {
                    Block::Netherrack
                };
            }
        }

        if let Some((inner, outer)) = self.core {
            if r >= inner && r <= outer {
                return Block::Bedrock;
            }
        }

        Block::Air
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Planet {
    /// Surface radius in blocks. No limit: the engine never stores the whole
    /// planet, it stores the window the player is in.
    pub radius: f64,
    pub seed: u64,
    pub shells: ShellModel,
    /// Peak terrain offset from the mean radius, in blocks.
    pub terrain_amplitude: f64,
    /// How far below the mean radius the sea sits.
    pub sea_level_drop: f64,
}

impl Planet {
    pub fn new(radius: u64, seed: u64) -> Self {
        let r = radius as f64;
        Self {
            radius: r,
            seed,
            shells: ShellModel::default(),
            // Relative, with an absolute ceiling so Earth's terrain stays
            // Minecraft-sized rather than continental.
            terrain_amplitude: (r * 0.01).clamp(2.0, 64.0),
            sea_level_drop: (r * 0.002).clamp(1.0, 10.0),
        }
    }

    /// Surface radius along a direction: the mean radius plus 3D noise.
    pub fn surface_radius_at(&self, dir: Vec3) -> f64 {
        // Frequency in direction space: features scale with the planet, so a
        // small moon and a huge world both get continents rather than one
        // giant blotch or per-block static.
        let n = noise::fbm(dir * 3.0, self.seed, 4);
        self.radius + n * self.terrain_amplitude
    }

    /// Crust thickness: relative for tiny worlds, capped at [`MAX_CRUST`].
    pub fn crust_thickness(&self) -> f64 {
        (self.radius * 0.20).clamp(8.0, MAX_CRUST)
    }

    /// Radial bands for one direction. One noise sample per column.
    pub fn column_plan(&self, dir: Vec3) -> ColumnPlan {
        let surface = self.surface_radius_at(dir);
        let sea = self.radius - self.sea_level_drop;
        let crust_outer = surface;
        let crust_inner = (surface - self.crust_thickness()).max(0.0);

        let core_outer = self.radius * 0.60;
        let core_inner = (core_outer - CORE_SHELL).max(self.radius * 0.58);
        let core = (core_outer > core_inner && core_outer < crust_inner)
            .then_some((core_inner, core_outer));

        let ring_inner = self.radius * 0.70;
        let ring_outer =
            (ring_inner + (self.radius * 0.02).clamp(2.0, 256.0)).min(crust_inner - 1.0);
        let below_core = core.map(|(_, outer)| outer).unwrap_or(0.0);
        let ring = (ring_inner > below_core && ring_outer > ring_inner)
            .then_some((ring_inner, ring_outer));

        ColumnPlan {
            surface,
            sea,
            crust: (crust_inner, crust_outer),
            ring,
            core,
        }
    }

    /// The block at a planet-space point. `p` may be at any distance.
    pub fn block_at(&self, p: Vec3) -> Block {
        let r = p.length();
        if r == 0.0 || !r.is_finite() {
            return Block::Air;
        }
        let dir = p / r;
        self.column_plan(dir).block_at(self.seed, dir, r)
    }

    /// The block's address (sector, shell, chunk, block).
    pub fn address_of(&self, p: Vec3) -> BlockAddress {
        BlockAddress::from_position(&self.shells, p)
    }

    /// Shell address above the core radius, Cartesian core address below it.
    pub fn address_space_of(&self, p: Vec3) -> AddressSpace {
        if p.length() < MIN_SHELL_RADIUS {
            AddressSpace::Core(CoreAddress::from_position(p))
        } else {
            AddressSpace::Shell(BlockAddress::from_position(&self.shells, p))
        }
    }

    /// Geometry the game may use for a solid block, or `None` when the block is
    /// not solid, is outside the planet, or its shape fails validation.
    ///
    /// This is the single gate that keeps depth math out of collision and
    /// rendering: nothing unvalidated is ever returned.
    pub fn geometry_at(&self, p: Vec3) -> Option<BlockGeometry> {
        if !self.block_at(p).is_solid() {
            return None;
        }
        let r = p.length();
        if r < MIN_SHELL_RADIUS {
            let min = CoreAddress::from_position(p).min();
            return Some(BlockGeometry::CoreCube {
                min,
                max: min + Vec3::splat(1.0),
            });
        }
        let shape = BlockShape::from_address(&self.shells, &self.address_of(p));
        shape.is_valid().then_some(BlockGeometry::Frustum(shape))
    }

    /// How many shells this planet uses.
    pub fn shell_count(&self) -> u32 {
        self.shells.shell_count(self.radius)
    }

    /// Gravity acceleration at a radius, for the mod's motion model: constant
    /// above the surface (predictable gameplay), falling to zero towards the
    /// centre, where the planet pulls equally in every direction.
    pub fn gravity_at(&self, r: f64, surface_g: f64) -> f64 {
        let surface = self.radius.max(1.0);
        if r >= surface {
            surface_g
        } else {
            surface_g * (r / surface).max(0.0)
        }
    }

    /// Unit gravity direction at a point: towards the planet centre.
    pub fn gravity_direction(&self, p: Vec3) -> Vec3 {
        -p.normalized()
    }
}
