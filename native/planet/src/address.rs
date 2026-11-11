//! Block addresses: the planet's "postal address", sector -> shell -> chunk ->
//! block.
//!
//! Two spaces are addressed here. The **shell space** covers everything at or
//! above [`crate::shape::MIN_SHELL_RADIUS`]; the **core space** is a plain
//! Cartesian grid inside that radius, where frustums would collapse to zero
//! width.

use crate::sector::Sector;
use crate::shell::ShellModel;
use crate::vec::Vec3;
use crate::CHUNK_SIZE;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct BlockAddress {
    pub sector: Sector,
    pub shell: u32,
    /// Index along the face's u axis, in `0..resolution(shell)`.
    pub i: u128,
    /// Index along the face's v axis, in `0..resolution(shell)`.
    pub j: u128,
    /// Radial layer inside the shell, in `0..layers(shell)`.
    pub layer: u128,
}

impl BlockAddress {
    /// Chunk index and block index inside that chunk.
    pub fn chunk(&self) -> (u128, u128, u128, [u128; 3]) {
        (
            self.i / CHUNK_SIZE,
            self.layer / CHUNK_SIZE,
            self.j / CHUNK_SIZE,
            [
                self.i % CHUNK_SIZE,
                self.layer % CHUNK_SIZE,
                self.j % CHUNK_SIZE,
            ],
        )
    }

    /// The address of the block at a planet-space point.
    ///
    /// Face coordinates are normalized to `[0, 1)`, scaled to the shell's
    /// resolution and floored; the radius is inverse-lerped into the shell's
    /// layers. No fixed world height is involved anywhere.
    pub fn from_position(shells: &ShellModel, p: Vec3) -> BlockAddress {
        let (sector, u, v) = Sector::from_planet(p);
        let r = p.length();
        let (shell, layer) = shells.locate(r);
        let n = shells.resolution(shell);
        let i = (((u + 1.0) * 0.5) * n as f64) as u128;
        let j = (((v + 1.0) * 0.5) * n as f64) as u128;
        BlockAddress {
            sector,
            shell,
            i: i.min(n.saturating_sub(1)),
            j: j.min(n.saturating_sub(1)),
            layer,
        }
    }

    /// Centre of this block in planet space.
    pub fn center(&self, shells: &ShellModel) -> Vec3 {
        shells.block_center(self.sector, self.shell, self.i, self.j, self.layer)
    }

    /// Blocks directly above this one.
    ///
    /// Inside a shell that is one block. At the top of a shell the next shell
    /// has twice the resolution per side, so the single block becomes a 2x2
    /// group: the article's "one block can have four neighbors in the up
    /// direction". Structures must pick a shell before they are placed,
    /// otherwise their roofs shrink by this factor.
    pub fn above(&self, shells: &ShellModel) -> Vec<BlockAddress> {
        if self.layer + 1 < shells.layers(self.shell) {
            return vec![BlockAddress {
                layer: self.layer + 1,
                ..*self
            }];
        }
        if self.shell >= 60 {
            return Vec::new();
        }
        let mut out = Vec::with_capacity(4);
        for di in 0..2u128 {
            for dj in 0..2u128 {
                out.push(BlockAddress {
                    shell: self.shell + 1,
                    i: self.i * 2 + di,
                    j: self.j * 2 + dj,
                    layer: 0,
                    sector: self.sector,
                });
            }
        }
        out
    }

    /// Blocks directly below this one: the inverse of [`BlockAddress::above`].
    pub fn below(&self, shells: &ShellModel) -> Vec<BlockAddress> {
        if self.layer > 0 {
            return vec![BlockAddress {
                layer: self.layer - 1,
                ..*self
            }];
        }
        if self.shell == 0 {
            return Vec::new();
        }
        let prev = self.shell - 1;
        vec![BlockAddress {
            shell: prev,
            i: self.i / 2,
            j: self.j / 2,
            layer: shells.layers(prev).saturating_sub(1),
            sector: self.sector,
        }]
    }
}

/// A unit cube in planet space, used only inside [`crate::shape::MIN_SHELL_RADIUS`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct CoreAddress {
    pub x: i64,
    pub y: i64,
    pub z: i64,
}

impl CoreAddress {
    pub fn from_position(p: Vec3) -> Self {
        fn block(v: f64) -> i64 {
            if v.is_finite() {
                v.floor().clamp(i64::MIN as f64, i64::MAX as f64) as i64
            } else {
                0
            }
        }
        Self {
            x: block(p.x),
            y: block(p.y),
            z: block(p.z),
        }
    }

    pub fn center(&self) -> Vec3 {
        Vec3::new(
            self.x as f64 + 0.5,
            self.y as f64 + 0.5,
            self.z as f64 + 0.5,
        )
    }

    /// The cube's eight corners.
    pub fn min(&self) -> Vec3 {
        Vec3::new(self.x as f64, self.y as f64, self.z as f64)
    }
}

/// Which addressing space a point belongs to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AddressSpace {
    Shell(BlockAddress),
    Core(CoreAddress),
}

impl AddressSpace {
    pub fn is_core(self) -> bool {
        matches!(self, AddressSpace::Core(_))
    }
}
