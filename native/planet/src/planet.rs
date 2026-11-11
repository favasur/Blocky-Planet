//! The planet itself: radius, terrain and block lookup, with no upper bound.
//!
//! A radius is an `f64` over a `u64` input, so a planet may be a 500-block
//! moon or 9.2e18 blocks across; the shell model only needs ~log2(r) shells.

use crate::address::BlockAddress;
use crate::noise;
use crate::shell::ShellModel;
use crate::vec::Vec3;

/// Salt for the Nether ring's noise, so it does not echo the surface.
const NETHER_SALT: u64 = 0x4E45_5448_4552_0001;

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
        Self {
            radius: radius as f64,
            seed,
            shells: ShellModel::default(),
            terrain_amplitude: 64.0,
            sea_level_drop: 10.0,
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

    /// The block at a planet-space point. `p` may be at any distance.
    pub fn block_at(&self, p: Vec3) -> Block {
        let r = p.length();
        if r == 0.0 {
            return Block::Air;
        }
        let dir = p / r;

        let surface = self.surface_radius_at(dir);
        let sea = self.radius - self.sea_level_drop;

        if r > surface {
            return if r <= sea { Block::Water } else { Block::Air };
        }

        // Hollow core, as in the article: digging all the way down reaches
        // void, with a bedrock shell around it.
        let hollow = self.radius * 0.58;
        let core = self.radius * 0.60;
        if r < hollow {
            return Block::Air;
        }
        if r < core {
            return Block::Bedrock;
        }

        // The Nether as a spherical ring deep inside the planet.
        let ring_inner = self.radius * 0.70;
        let ring_outer = ring_inner + (self.radius * 0.02).max(32.0);
        if r >= ring_inner && r <= ring_outer {
            let n = noise::fbm(dir * 12.0, self.seed ^ NETHER_SALT, 3);
            return if n > 0.45 {
                Block::Lava
            } else {
                Block::Netherrack
            };
        }

        let depth = surface - r;
        if depth < 1.0 {
            Block::Grass
        } else if depth < 4.0 {
            Block::Dirt
        } else if depth < 96.0 {
            Block::Stone
        } else {
            Block::Deepslate
        }
    }

    /// The block's address (sector, shell, chunk, block).
    pub fn address_of(&self, p: Vec3) -> BlockAddress {
        BlockAddress::from_position(&self.shells, p)
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
