//! Block addresses: the planet's "postal address", sector -> shell -> chunk ->
//! block, exactly as in the article.

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
}
