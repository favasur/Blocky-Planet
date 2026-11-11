//! The planet: radius, layers, terrain and block lookup, with no upper bound.
//!
//! # Solid planet, bounded generation
//!
//! An Earth-like planet is **solid**: the mantle and core exist all the way to
//! the centre. Walking a whole column would be 6.37 million blocks, and the
//! failed Java version paid for that twice — once iterating every Y, once
//! calling noise per block.
//!
//! This model never walks a column. Layer profiles classify a *radius* in O(1)
//! (see `layers.rs`), and generation is a bounded band around the surface or
//! the player. A surface band 256 blocks deep is 0.004% of the radius; the
//! window budget in `stream.rs` refuses anything larger. The planet can be
//! solid and still generation is local.

use crate::address::{AddressSpace, BlockAddress, CoreAddress};
use crate::layers::{LayerProfile, PlanetKind, PlanetSettings, SettingsError};
use crate::noise;
use crate::shape::{BlockGeometry, BlockShape, MIN_SHELL_RADIUS};
use crate::shell::ShellModel;
use crate::vec::Vec3;

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

/// The surface of one column: everything needed to classify a radius without
/// resampling the terrain noise.
#[derive(Clone, Copy, Debug)]
pub struct Column {
    pub surface: f64,
    pub sea: f64,
}

impl Column {
    pub fn block_at(&self, planet: &Planet, _dir: Vec3, r: f64) -> Block {
        if r > self.surface {
            return if planet.profile.has_ocean() && r <= self.sea {
                Block::Water
            } else {
                Block::Air
            };
        }

        let depth = self.surface - r;
        if depth < 1.0 {
            return planet.profile.surface_block();
        }
        if depth < 4.0 {
            return planet.profile.subsurface_block();
        }

        let fraction = (r / planet.radius.max(1.0)).clamp(0.0, 1.0);
        planet.profile.block_at(fraction)
    }
}

#[derive(Clone, Debug)]
pub struct Planet {
    /// Surface radius in blocks. No limit: the engine never stores the whole
    /// planet, it stores the window the player is in.
    pub radius: f64,
    pub seed: u64,
    pub shells: ShellModel,
    pub profile: LayerProfile,
    /// Peak terrain offset from the mean radius, in blocks.
    pub terrain_amplitude: f64,
    /// How far below the mean radius the sea sits.
    pub sea_level_drop: f64,
}

impl Planet {
    pub fn new(radius: u64, seed: u64) -> Self {
        Self::with_profile(radius, seed, LayerProfile::for_kind(PlanetKind::EarthLike))
    }

    pub fn with_profile(radius: u64, seed: u64, profile: LayerProfile) -> Self {
        let r = radius as f64;
        Self {
            radius: r,
            seed,
            shells: ShellModel::default(),
            profile,
            // Relative, with an absolute ceiling so Earth's terrain stays
            // Minecraft-sized rather than continental.
            terrain_amplitude: (r * 0.01).clamp(2.0, 64.0),
            sea_level_drop: (r * 0.002).clamp(1.0, 10.0),
        }
    }

    pub fn from_settings(settings: &PlanetSettings) -> Result<Self, SettingsError> {
        let profile = settings.profile()?;
        Ok(Self::with_profile(
            settings.diameter_blocks(),
            settings.seed,
            profile,
        ))
    }

    /// Surface radius along a direction: the mean radius plus 3D noise.
    pub fn surface_radius_at(&self, dir: Vec3) -> f64 {
        // Frequency in direction space: features scale with the planet, so a
        // small moon and a huge world both get continents rather than one
        // giant blotch or per-block static.
        let n = noise::fbm(dir * 3.0, self.seed, 4);
        self.radius + n * self.terrain_amplitude
    }

    /// Surface column: one noise sample, reused for every block in it.
    pub fn column(&self, dir: Vec3) -> Column {
        Column {
            surface: self.surface_radius_at(dir),
            sea: self.radius - self.sea_level_drop,
        }
    }

    pub fn crust_thickness(&self) -> f64 {
        self.profile.layers[0].thickness_fraction() * self.radius
    }

    /// The block at a planet-space point. `p` may be at any distance.
    pub fn block_at(&self, p: Vec3) -> Block {
        let r = p.length();
        if !r.is_finite() {
            return Block::Air;
        }
        if r == 0.0 {
            return self.profile.block_at(0.0);
        }
        let dir = p / r;
        self.column(dir).block_at(self, dir, r)
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
    /// not solid or its shape fails validation.
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

    /// Gravity acceleration at a radius: constant above the surface,
    /// falling to zero towards the centre.
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
